const { test } = require('node:test')
const assert = require('node:assert/strict')
const React = require('react')
const { act, create } = require('react-test-renderer')
const load = require('./loadInboxModule.cjs')
const pause = ms => new Promise(resolve => setTimeout(resolve, ms))
const deferred = () => { let resolve, reject; const promise = new Promise((a,b) => { resolve=a; reject=b }); return {promise,resolve,reject} }
function environment(t) {
    const previous = Object.fromEntries(['window','document','localStorage','CustomEvent','fetch'].map(key => [key,global[key]]))
    global.window = new EventTarget()
    global.document = Object.assign(new EventTarget(), { hidden:false })
    const values = new Map()
    global.localStorage = { getItem:key=>values.get(key)??null, setItem:(key,value)=>values.set(key,value), removeItem:key=>values.delete(key) }
    global.CustomEvent = class extends Event { constructor(type,init) { super(type);this.detail=init?.detail } }
    return () => Object.assign(global,previous)
}
function authentication(t, overrides={}) {
    const restore = environment(t)
    let token='A', state, renderer, Child
    const storage={getAccessToken:()=>token,setAccessToken:value=>{token=value},clearAccessToken:()=>{token=null}}
    const module=load('../../auth/AuthContext.tsx',{
        './authStorage':storage,
        './authApi':{login:async()=>({accessToken:'B'}),...overrides.login},
        '../api/employeeApi':{getCurrentEmployee:async()=>({id:token,organizationId:'O',role:'EMPLOYEE',enabled:true}),...overrides.employee},
        '../api/employeeWorkspaceApi':{getCurrentEmployeeWorkspaces:async()=>[],...overrides.memberships},
        '../api/organizationApi':{getOrganization:async()=>({id:'O'})},
    })
    function Probe(){state=module.useAuth();return Child ? React.createElement(Child) : null}
    t.after(async()=>{try { await act(async()=>renderer?.unmount()) } finally { restore() }})
    return { module,storage,get state(){return state},get token(){return token},
        setToken:value=>{token=value},
        mount:async child=>{Child=child;await act(async()=>{renderer=create(React.createElement(module.AuthProvider,null,React.createElement(Probe)))})} }
}
function provider(t, mocks={}, policy) {
    const restore = environment(t)
    const context=load('../../api/events/InvalidationContext.tsx')
    const scope=load('../../api/events/uiScope.ts')
    let stream, renderer, workspace='W-A', logout=0, identityReads=0, workspaceReads=0
    const auth={accessToken:'A',currentEmployee:{id:'E',organizationId:'O',role:'EMPLOYEE'},employeeWorkspaces:[],
        refreshCurrentUser:async()=>{identityReads++},invalidateIdentity:()=>{},logout:()=>{logout++},synchronizeSession:()=>{}}
    const workspaceValue={currentWorkspace:{id:workspace},setCurrentWorkspaceId:()=>{},refreshWorkspaces:async()=>{workspaceReads++},invalidateWorkspaces:()=>{}}
    const workspaceContext={useWorkspace:()=>workspaceValue}
    const boundaries={
        './InvalidationContext':context,'../../api/events/InvalidationContext':context,'../../../api/events/InvalidationContext':context,
        './uiScope':scope,'../../api/events/uiScope':scope,'../../../api/events/uiScope':scope,
        '../../auth/AuthContext':{useAuth:()=>auth},'../../auth/authStorage':{getAccessToken:()=>auth.accessToken},
        '../../workspace/WorkspaceContext':workspaceContext,
        './uiEventStream':{startUiEventStream:options=>{stream=options;return {stop(){}}}},...mocks,
    }
    const {UiEventsProvider}=load('../../api/events/UiEventsProvider.tsx',boundaries)
    let Child
    const tree=()=>React.createElement(UiEventsProvider,{policy},React.createElement(Child))
    t.after(async()=>{try { await act(async()=>renderer?.unmount()) } finally { restore() }})
    return {auth,scopeModule:scope,load:(path,extra={})=>load(path,{...boundaries,...extra}),get stream(){return stream},
        get renderer(){return renderer},get counters(){return {logout,identityReads,workspaceReads}},
        mount:async (child,options)=>{Child=child;await act(async()=>{renderer=create(tree(),options)})},
        switch:async id=>{workspace=id;workspaceValue.currentWorkspace={id};await act(async()=>renderer.update(tree()))},
    }
}
const message=(id,conversationId='C')=>({id,conversationId,sentAt:'2026-10-22T08:00:00Z',createdAt:'2026-10-22T08:00:00Z',direction:'INBOUND'})
const page=id=>({content:[message(id)],last:true})

for (const outcome of ['success','failure']) test(`late profile ${outcome} from A cannot alter authenticated B`,async t=>{
    const old=deferred();let calls=0,oldSignal
    const ui=authentication(t,{employee:{getCurrentEmployee:async signal=>{
        if(++calls===2){oldSignal=signal;return old.promise}
        return {id:calls===1?'A':'B',organizationId:'O',role:'EMPLOYEE',enabled:true}
    }}})
    await ui.mount();let pending
    await act(async()=>{pending=ui.state.refreshCurrentUser().catch(error=>error)})
    await act(async()=>ui.state.logout())
    assert.equal(oldSignal.aborted,true)
    await act(async()=>ui.state.login('b',undefined,'test-only'))
    await act(async()=>{
        if(outcome==='success') old.resolve({id:'A',organizationId:'O',role:'EMPLOYEE',enabled:true})
        else old.reject(Object.assign(new Error('old failure'),{status:401}))
        await pending
    })
    assert.equal(ui.state.currentEmployee.id,'B');assert.equal(ui.token,'B')
})

test('storage account switch aborts old reads and reloads the new identity',async t=>{
    const old=deferred();let calls=0,oldSignal
    const ui=authentication(t,{employee:{getCurrentEmployee:async signal=>{
        if(++calls===2){oldSignal=signal;return old.promise}
        return {id:calls===1?'A':'B',organizationId:'O',role:'EMPLOYEE',enabled:true}
    }}})
    await ui.mount();let pending
    await act(async()=>{pending=ui.state.refreshCurrentUser().catch(error=>error)})
    await act(async()=>{
        ui.setToken('B')
        window.dispatchEvent(Object.assign(new Event('storage'),{key:'clientbus.accessToken'}))
    })
    assert.equal(oldSignal.aborted,true);assert.equal(ui.state.currentEmployee.id,'B')
    await act(async()=>{old.resolve({id:'A',organizationId:'O'});await pending})
    assert.equal(ui.state.currentEmployee.id,'B')
})

test('logout invalidates a pending login response',async t=>{
    const login=deferred(),ui=authentication(t,{login:{login:()=>login.promise}})
    await ui.mount();let pending
    await act(async()=>{pending=ui.state.login('b',undefined,'test-only').catch(error=>error)})
    await act(async()=>ui.state.logout())
    await act(async()=>{login.resolve({accessToken:'B'});await pending})
    assert.equal(ui.state.isAuthenticated,false);assert.equal(ui.token,null)
})

test('REST 401 expires the current session; a late response for A cannot expire B',async t=>{
    const ui=authentication(t);await ui.mount()
    const {apiFetch}=load('../../api/apiClient.ts',{'../auth/authStorage':ui.storage})
    const old=deferred();global.fetch=()=>old.promise
    const request=apiFetch('/api/private').catch(error=>error)
    await act(async()=>ui.state.logout());await act(async()=>ui.state.login('b',undefined,'test-only'))
    await act(async()=>{old.resolve(new Response('{}',{status:401}));await request})
    assert.equal(ui.state.currentEmployee.id,'B')
    global.fetch=async()=>new Response('{}',{status:401})
    await act(async()=>{await apiFetch('/api/private').catch(()=>{})})
    assert.equal(ui.state.isAuthenticated,false)
})

test('SSE unauthorized ends local authentication; forbidden refreshes identity and Workspace',async t=>{
    const ui=provider(t)
    await ui.mount(()=>null)
    await act(async()=>{ui.stream.onState('forbidden');await pause(150)})
    assert.ok(ui.counters.identityReads>0);assert.ok(ui.counters.workspaceReads>0)
    assert.equal(ui.counters.logout,0)
    await act(async()=>ui.stream.onState('unauthorized'))
    assert.equal(ui.counters.logout,1)
})

test('access invalidation cancels the initial Inbox read before ready and rejects its late response',async t=>{
    const ui=provider(t),old=deferred();let calls=0,signal,state
    const {useInboxConversations}=ui.load('list/useInboxConversations.ts',{
        './inboxData':{loadInboxConversations:async(_w,_q,_current,s)=>{
            if(++calls===1){signal=s;return old.promise}
            throw Object.assign(new Error('denied'),{status:403})
        }},
    })
    function Probe(){state=useInboxConversations('W-A',{view:'MINE'},true);return null}
    await ui.mount(Probe)
    const reconciliation=ui.stream.onReady({},new AbortController().signal).catch(error=>error)
    await act(async()=>{
        ui.stream.onEvent({type:'access.invalidated',workspaceId:'W-A',resource:{kind:'workspace',id:'W-A'}})
        await reconciliation
    })
    assert.equal(signal.aborted,true)
    await act(async()=>{old.resolve({items:[{conversation:{id:'obsolete'}}],channels:[]});await pause(1)})
    assert.deepEqual(state.items,[])
})

test('Workspace A-B-A aborts manual message reads and does not revive the first A session',async t=>{
    const ui=provider(t),old=deferred();let calls=0,signal,state
    const {useConversationMessages}=ui.load('useConversationMessages.ts',{
        '../../api/messageApi':{getConversationMessages:async(_id,_p,_s,requestSignal)=>{
            if(++calls===2){signal=requestSignal;return old.promise}
            return page('current-'+calls)
        }},
    })
    function Probe(){state=useConversationMessages('C');return null}
    await ui.mount(Probe);let pending
    await act(async()=>{pending=state.refreshMessages()})
    await ui.switch('W-B');assert.equal(signal.aborted,true)
    await ui.switch('W-A')
    await act(async()=>{old.resolve(page('obsolete'));await pending})
    assert.deepEqual(state.messages.map(value=>value.id),['current-4'])
})

test('ready refreshes group/filter catalogues and late GETs preserve completed local commands',async t=>{
    const ui=provider(t),group=deferred(),filter=deferred();let groups,filters,g=0,f=0
    const {useConversationGroups}=ui.load('groups/useConversationGroups.ts',{
        '../../../api/conversationApi':{getGroups:async()=>++g===2?group.promise:[],
            createGroup:async()=>({id:'new-group',name:'Created'})},
    })
    const {useSavedConversationFilters}=ui.load('filters/useSavedConversationFilters.ts',{
        '../../../api/savedConversationFilterApi':{getSavedConversationFilters:async(_w,type)=>type==='PRIVATE'&&++f===2?filter.promise:[],
            createSavedConversationFilter:async()=>({id:'new-filter',filterType:'PRIVATE',name:'Created'})},
    })
    function Probe(){groups=useConversationGroups('W-A',true);filters=useSavedConversationFilters('W-A',{isEmployee:true,isSuperAdmin:false,employeeId:'E'});return null}
    await ui.mount(Probe)
    const ready=ui.stream.onReady({},new AbortController().signal)
    await act(async()=>{await pause(1);await groups.create('Created','icon');await filters.save('Created',{})})
    await act(async()=>{group.resolve([]);filter.resolve([]);await ready})
    assert.equal(groups.groups[0].id,'new-group');assert.equal(filters.privateFilters[0].id,'new-filter')
    assert.ok(g>=2);assert.ok(f>=2)
})

test('an access signal for another Workspace does not clear the current Inbox',async t=>{
    const ui=provider(t);let state
    const {useInboxConversations}=ui.load('list/useInboxConversations.ts',{
        './inboxData':{loadInboxConversations:async()=>({items:[{conversation:{id:'allowed'}}],channels:[]})},
    })
    function Probe(){state=useInboxConversations('W-A',{view:'MINE'},true);return null}
    await ui.mount(Probe)
    await act(async()=>ui.stream.onEvent({type:'access.invalidated',workspaceId:'W-B',resource:{kind:'workspace',id:'W-B'}}))
    assert.equal(state.items[0].conversation.id,'allowed')
})

test('Workspace catalogue from A cannot select or populate the Workspace of B',async t=>{
    const ui=authentication(t),old=deferred();let state,oldSignal,calls=0
    const module=load('../../workspace/WorkspaceContext.tsx',{
        '../auth/AuthContext':ui.module,'../auth/authStorage':ui.storage,'../api/workspaceApi':{getCurrentUserWorkspaces:async signal=>{
            if(++calls===1){oldSignal=signal;return old.promise}
            return [{id:'B-workspace'}]
        }},
    })
    function Probe(){state=module.useWorkspace();return null}
    function Child(){return React.createElement(module.WorkspaceProvider,null,React.createElement(Probe))}
    await ui.mount(Child)
    await act(async()=>ui.state.logout());await act(async()=>ui.state.login('b',undefined,'test-only'))
    assert.equal(oldSignal.aborted,true);assert.equal(state.currentWorkspace.id,'B-workspace')
    await act(async()=>old.resolve([{id:'A-workspace'}]))
    assert.deepEqual(state.workspaces.map(item=>item.id),['B-workspace'])
})

test('a catalogue refresh preserves a newer explicit Workspace selection',async t=>{
    const ui=authentication(t),update=deferred();let state,calls=0
    const module=load('../../workspace/WorkspaceContext.tsx',{
        '../auth/AuthContext':ui.module,'../auth/authStorage':ui.storage,'../api/workspaceApi':{getCurrentUserWorkspaces:async()=>++calls===1?[{id:'W-A'},{id:'W-B'}]:update.promise},
    })
    function Probe(){state=module.useWorkspace();return null}
    function Child(){return React.createElement(module.WorkspaceProvider,null,React.createElement(Probe))}
    await ui.mount(Child);let pending
    await act(async()=>{pending=state.refreshWorkspaces()})
    await act(async()=>state.setCurrentWorkspaceId('W-B'))
    await act(async()=>{update.resolve([{id:'W-A'},{id:'W-B'}]);await pending})
    assert.equal(state.currentWorkspace.id,'W-B')
})

test('real conversation SSE refresh preserves the composer draft and manual scroll position',async t=>{
    const ui=provider(t),incoming=deferred();let reads=0
    ui.auth.isEmployee=true;ui.auth.isSuperAdmin=false
    const base={id:'C',workspaceId:'W-A',clientAccountId:'Q',assignedEmployeeId:'E',unreadCount:0}
    const View=ui.load('ConversationView.tsx',{
        'react-router-dom':{useParams:()=>({conversationId:'C'}),useNavigate:()=>()=>{}},
        '../../api/conversationApi':{getConversation:async()=>base},
        '../../api/clientAccountApi':{getClientAccount:async()=>({id:'Q'})},
        '../../api/channelApi':{getWorkspaceChannels:async()=>[]},
        '../../api/messageApi':{getConversationMessages:async()=>++reads===2?incoming.promise:page('initial')},
        './ConversationHeader':{default:()=>React.createElement('header')},
        './ConversationClientPanel':{default:()=>null},'./MessageBubble':{default:()=>null},
    }).default
    const container={scrollTop:0,scrollHeight:1000,clientHeight:300}
    await ui.mount(View,{createNodeMock:element=>element.props.className==='message-list'?container:null})
    const textarea=ui.renderer.root.findByType('textarea')
    await act(async()=>textarea.props.onChange({target:{value:'Unsent draft'}}))
    container.scrollTop=200
    await act(async()=>ui.renderer.root.findByProps({className:'message-list'}).props.onScroll())
    await act(async()=>{ui.stream.onEvent({type:'messages.invalidated',workspaceId:'W-A',resource:{kind:'conversation',id:'C'}});await pause(150)})
    container.scrollHeight=1100
    await act(async()=>{incoming.resolve(page('new'));await pause(1)})
    assert.equal(ui.renderer.root.findByType('textarea'),textarea)
    assert.equal(textarea.props.value,'Unsent draft');assert.equal(container.scrollTop,200)
})

test('a local conversation update during ready keeps its Inbox read and queues a fresh snapshot',async t=>{
    const ui=provider(t),fresh=deferred();let calls=0,signal,state
    const {useInboxConversations}=ui.load('list/useInboxConversations.ts',{
        './inboxData':{loadInboxConversations:async(_w,_q,_current,s)=>{
            if(++calls===2){signal=s;return fresh.promise}
            return {items:[],channels:[]}
        }},
    })
    function Probe(){state=useInboxConversations('W-A',{view:'MINE'},true);return null}
    await ui.mount(Probe)
    const ready=ui.stream.onReady({},new AbortController().signal)
    const result=ready.then(()=>null,error=>error)
    await act(async()=>{await pause(1);window.dispatchEvent(new CustomEvent('clientbus:conversation-updated',{detail:{id:'C',workspaceId:'W-A'}}))})
    assert.equal(signal.aborted,false)
    await act(async()=>{fresh.resolve({items:[],channels:[]});await ready;await pause(350)})
    assert.equal(await result,null);assert.ok(calls>=3);assert.equal(state.error,null)
})

for (const control of ['access.invalidated','identity.invalidated','forbidden']) test(`${control} replaces a manual identity read started before revocation`,async t=>{
    const old=deferred();let role='SUPER_ADMIN',memberships=0,reads=0,oldSignal,stream
    const ui=authentication(t,{
        employee:{getCurrentEmployee:async()=>{reads++;return {id:'E',organizationId:'O',role,enabled:true}}},
        memberships:{getCurrentEmployeeWorkspaces:async signal=>{
            if(++memberships===2){oldSignal=signal;return old.promise}
            return []
        }},
    })
    const workspace={currentWorkspace:{id:'W-A'},refreshWorkspaces:async()=>{},invalidateWorkspaces:()=>{}}
    const {UiEventsProvider}=load('../../api/events/UiEventsProvider.tsx',{
        '../../auth/AuthContext':ui.module,'../../auth/authStorage':ui.storage,
        '../../workspace/WorkspaceContext':{useWorkspace:()=>workspace},
        './uiEventStream':{startUiEventStream:options=>{stream=options;return {stop(){}}}},
    })
    await ui.mount(()=>React.createElement(UiEventsProvider))
    assert.equal(ui.state.currentEmployee.role,'SUPER_ADMIN')
    let pending
    await act(async()=>{pending=ui.state.refreshCurrentUser().catch(error=>error)})
    role='EMPLOYEE'
    await act(async()=>{
        if(control==='forbidden') stream.onState(control)
        else stream.onEvent({type:control,workspaceId:'W-A',resource:{kind:'workspace',id:'W-A'}})
        await pause(180)
    })
    assert.equal(oldSignal.aborted,true)
    assert.ok(reads>=3,'Revocation must obtain a new /me snapshot')
    assert.equal(ui.state.currentEmployee.role,'EMPLOYEE')
    await act(async()=>{old.reject(Object.assign(new Error('stale authorization failure'),{status:401}));await pending})
    assert.equal(ui.state.currentEmployee.role,'EMPLOYEE');assert.equal(ui.token,'A')
})

test('access control cancels a manual Workspace catalogue and rejects its obsolete membership',async t=>{
    const ui=authentication(t),old=deferred();let state,reads=0,oldSignal,stream
    const workspaceModule=load('../../workspace/WorkspaceContext.tsx',{
        '../auth/AuthContext':ui.module,'../auth/authStorage':ui.storage,
        '../api/workspaceApi':{getCurrentUserWorkspaces:async signal=>{
            if(++reads===2){oldSignal=signal;return old.promise}
            return reads===1?[{id:'W-A'},{id:'W-B'}]:[{id:'W-A'}]
        }},
    })
    const {UiEventsProvider}=load('../../api/events/UiEventsProvider.tsx',{
        '../../auth/AuthContext':ui.module,'../../auth/authStorage':ui.storage,
        '../../workspace/WorkspaceContext':workspaceModule,
        './uiEventStream':{startUiEventStream:options=>{stream=options;return {stop(){}}}},
    })
    function Probe(){state=workspaceModule.useWorkspace();return React.createElement(UiEventsProvider)}
    await ui.mount(()=>React.createElement(workspaceModule.WorkspaceProvider,null,React.createElement(Probe)))
    let pending
    await act(async()=>{pending=state.refreshWorkspaces().catch(error=>error)})
    await act(async()=>{
        stream.onEvent({type:'access.invalidated',workspaceId:'W-B',resource:{kind:'workspace',id:'W-B'}})
        await pause(180)
    })
    assert.equal(oldSignal.aborted,true);assert.ok(reads>=3)
    await act(async()=>{old.resolve([{id:'W-A'},{id:'W-B'}]);await pending})
    assert.deepEqual(state.workspaces.map(item=>item.id),['W-A'])
    assert.equal(state.currentWorkspace.id,'W-A')
})

function conversationFixture(t,overrides={},realClientPanel=false) {
    const ui=provider(t)
    ui.auth.isEmployee=true;ui.auth.isSuperAdmin=false
    const base={id:'C',workspaceId:'W-A',clientAccountId:'Q',assignedEmployeeId:'E',unreadCount:0}
    let reads=0
    const mocks={
        'react-router-dom':{useParams:()=>({conversationId:'C'}),useNavigate:()=>()=>{}},
        '../../api/conversationApi':{getConversation:async()=>{reads++;return base}},
        '../../api/clientAccountApi':{getClientAccount:async()=>({id:'Q'})},
        '../../api/channelApi':{getWorkspaceChannels:async()=>[]},
        '../../api/messageApi':{getConversationMessages:async()=>page('initial')},
        './ConversationHeader':{default:props=>React.createElement('header',props)},
        './ConversationClientPanel':{default:()=>null},'./MessageBubble':{default:()=>null},
        ...overrides,
    }
    if(realClientPanel) delete mocks['./ConversationClientPanel']
    const View=ui.load('ConversationView.tsx',mocks).default
    ui.View=View;ui.base=base
    Object.defineProperty(ui,'reads',{get:()=>reads})
    return ui
}

test('membership changes in another Workspace preserve the active draft and scroll without reloading',async t=>{
    const ui=conversationFixture(t)
    ui.auth.employeeWorkspaces=[{workspaceId:'W-A',isSupervisor:false},{workspaceId:'W-B',isSupervisor:true}]
    const container={scrollTop:0,scrollHeight:1000,clientHeight:300}
    await ui.mount(ui.View,{createNodeMock:element=>element.props.className==='message-list'?container:null})
    const textarea=ui.renderer.root.findByType('textarea'),reads=ui.reads
    await act(async()=>textarea.props.onChange({target:{value:'Keep this draft'}}))
    container.scrollTop=200
    await act(async()=>ui.renderer.root.findByProps({className:'message-list'}).props.onScroll())
    ui.auth.employeeWorkspaces=[{workspaceId:'W-A',isSupervisor:false},{workspaceId:'W-B',isSupervisor:false}]
    await ui.switch('W-A')
    assert.equal(ui.renderer.root.findByType('textarea'),textarea)
    assert.equal(textarea.props.value,'Keep this draft');assert.equal(container.scrollTop,200)
    assert.equal(ui.reads,reads)
})

for (const outcome of ['conflict','success']) test(`an invalidation during assignment ${outcome} waits for a fresh owner snapshot`,async t=>{
    const old=deferred(),command=deferred();let reads=0
    const base={id:'C',workspaceId:'W-A',clientAccountId:'Q',assignedEmployeeId:'E',unreadCount:0}
    const fresh={...base,assignedEmployeeId:'OTHER'}
    const ui=conversationFixture(t,{
        '../../api/conversationApi':{getConversation:async()=>++reads===2?old.promise:reads===1?base:fresh,
            unassignConversationFromMe:()=>command.promise},
        './MessageComposer':{default:props=>React.createElement('textarea',props)},
    })
    await ui.mount(ui.View)
    let send,release
    await act(async()=>{send=ui.renderer.root.findByType('textarea').props.onSent(message('sent'))})
    assert.equal(reads,2)
    await act(async()=>{release=ui.renderer.root.findByType('header').props.onRelease()})
    await act(async()=>{
        ui.stream.onEvent({type:'inbox.invalidated',workspaceId:'W-A',resource:{kind:'conversation',id:'C'}})
        await pause(160)
    })
    assert.equal(reads,2,'Reconciliation must wait for the assignment command')
    await act(async()=>{
        if(outcome==='conflict') command.reject(Object.assign(new Error('assignment conflict'),{status:409}))
        else command.resolve(base)
        old.resolve(base)
        await send;await release;await pause(30)
    })
    assert.ok(reads>=3,'The earlier GET must not satisfy post-command reconciliation')
    assert.equal(ui.renderer.root.findByType('header').props.conversation.assignedEmployeeId,'OTHER')
    assert.equal(ui.renderer.root.findAllByType('textarea').length,0)
})

const clientVisualMocks={
    '../common/platform/PlatformIcon':{default:()=>null},
    '../common/platform/PlatformName':{default:()=>null},
    '../channels/ChannelAccountAvatar':{ChannelAccountAvatar:()=>null},
    '../clients/ClientAccountAvatar':{default:()=>null},
    '../clients/ClientAccountPicker':{default:props=>React.createElement('div',{'data-picker':true,...props})},
}

test('assignment conflict obtains a new owner after an older manual GET even without an SSE event',async t=>{
    const old=deferred(),command=deferred();let reads=0
    const base={id:'C',workspaceId:'W-A',clientAccountId:'Q',assignedEmployeeId:'E',unreadCount:0}
    const ui=conversationFixture(t,{
        '../../api/conversationApi':{getConversation:async()=>++reads===2?old.promise:reads===1?base:{...base,assignedEmployeeId:'OTHER'},
            unassignConversationFromMe:()=>command.promise},
        './MessageComposer':{default:props=>React.createElement('textarea',props)},
    })
    await ui.mount(ui.View)
    let send,release
    await act(async()=>{send=ui.renderer.root.findByType('textarea').props.onSent(message('sent'))})
    await act(async()=>{release=ui.renderer.root.findByType('header').props.onRelease()})
    await act(async()=>{
        command.reject(Object.assign(new Error('assignment conflict'),{status:409}))
        old.resolve(base)
        await send;await release
    })
    assert.equal(reads,3)
    assert.equal(ui.renderer.root.findByType('header').props.conversation.assignedEmployeeId,'OTHER')
    assert.equal(ui.renderer.root.findAllByType('textarea').length,0)
})
const renderedText=renderer=>JSON.stringify(renderer.toJSON())
const namedButton=(renderer,name)=>renderer.root.findAllByType('button').find(node=>node.children.join('')===name)
const clientAccount=(id='Q')=>({id,clientId:'client',externalId:id,displayName:id,channelType:'TELEGRAM'})
const clientProfile=(version=0)=>({id:'client',firstName:'Name'+version,lastName:'',phoneList:['phone'+version],enabled:true,avatarUrl:version?'avatar'+version:null})

test('client invalidation, ready and resync refresh the same client profile, avatar and accounts without closing its picker',async t=>{
    let version=0,profiles=0,accounts=0,avatars=0
    t.mock.method(URL,'createObjectURL',()=>`blob:client-${++avatars}`)
    t.mock.method(URL,'revokeObjectURL',()=>{})
    const ui=conversationFixture(t,{
        ...clientVisualMocks,
        '../../api/clientAccountApi':{getClientAccount:async()=>clientAccount()},
        '../../api/clientApi':{
            getClient:async()=>{profiles++;return clientProfile(version)},
            getClientAccountsByClient:async()=>{accounts++;return [clientAccount(),clientAccount('Other'+version)]},
            getClientAvatarContent:async()=>new Blob(),
        },
    },true)
    await ui.mount(ui.View)
    const panel=ui.renderer.root.findByProps({className:'conversation-client-panel'})
    await act(async()=>namedButton(ui.renderer,'Привязать еще аккаунт').props.onClick())
    const picker=ui.renderer.root.findByProps({'data-picker':true})
    await act(async()=>namedButton(ui.renderer,'Другие аккаунты (1)').props.onClick())
    for(const trigger of ['clients.invalidated','ready','resync']) {
        version++
        await act(async()=>{
            if(trigger==='ready') await ui.stream.onReady({},new AbortController().signal)
            else {
                if(trigger==='resync') ui.stream.onResync()
                else ui.stream.onEvent({type:trigger,workspaceId:'W-A',resource:{kind:'client',id:'client'}})
                await pause(330)
            }
        })
        assert.match(renderedText(ui.renderer),new RegExp('Name'+version))
        assert.match(renderedText(ui.renderer),new RegExp('phone'+version))
        assert.match(renderedText(ui.renderer),new RegExp('Other'+version))
        assert.equal(ui.renderer.root.findByProps({className:'conversation-client-panel'}),panel)
        assert.equal(ui.renderer.root.findByProps({'data-picker':true}),picker)
        assert.equal(picker.props.open,true)
    }
    assert.ok(profiles>=4);assert.ok(accounts>=4);assert.equal(avatars,3)
})

test('client resources reconcile an initial read and preserve a local account link against an older SSE snapshot',async t=>{
    const old=deferred();let reads=0
    const ui=provider(t)
    const Panel=ui.load('ConversationClientPanel.tsx',{
        ...clientVisualMocks,
        '../../api/clientApi':{
            getClient:async()=>clientProfile(),
            getClientAccountsByClient:async()=>++reads===1?old.promise:[clientAccount(),clientAccount('Linked')],
            assignClientAccount:async()=>clientAccount('Linked'),
        },
    }).default
    await ui.mount(()=>React.createElement(Panel,{conversation:{id:'C',workspaceId:'W-A'},clientAccount:clientAccount(),channel:null,onRetryDetails(){},onChanged(){}}))
    const ready=ui.stream.onReady({},new AbortController().signal)
    await act(async()=>{
        await pause(1)
        await namedButton(ui.renderer,'Привязать еще аккаунт').props.onClick()
        ui.renderer.root.findByProps({'data-picker':true}).props.onSelect({...clientAccount('Linked'),clientId:null})
    })
    await act(async()=>{old.resolve([]);await ready;await pause(1)})
    await act(async()=>namedButton(ui.renderer,'Другие аккаунты (1)').props.onClick())
    assert.match(renderedText(ui.renderer),/Linked/);assert.ok(reads>=2)
})

test('a client access reset cancels all reads and late denied data cannot reappear',async t=>{
    const oldProfile=deferred(),oldAccounts=deferred();let profileReads=0,accountReads=0,profileSignal,accountSignal
    const ui=provider(t)
    const denied=()=>{throw Object.assign(new Error('denied'),{status:403})}
    const Panel=ui.load('ConversationClientPanel.tsx',{
        ...clientVisualMocks,
        '../../api/clientApi':{
            getClient:async(_id,signal)=>{if(++profileReads===2){profileSignal=signal;return oldProfile.promise}return profileReads===1?clientProfile():denied()},
            getClientAccountsByClient:async(_id,signal)=>{if(++accountReads===2){accountSignal=signal;return oldAccounts.promise}return accountReads===1?[clientAccount('Private')]:denied()},
        },
    }).default
    await ui.mount(()=>React.createElement(Panel,{conversation:{id:'C',workspaceId:'W-A'},clientAccount:clientAccount(),channel:null,onRetryDetails(){},onChanged(){}}))
    const ready=ui.stream.onReady({},new AbortController().signal).catch(error=>error)
    await act(async()=>{
        await pause(1)
        ui.stream.onEvent({type:'access.invalidated',workspaceId:'W-A',resource:{kind:'conversation',id:'C'}})
        await ready
        await ui.stream.onReady({},new AbortController().signal)
    })
    assert.equal(profileSignal.aborted,true);assert.equal(accountSignal.aborted,true)
    await act(async()=>{oldProfile.resolve(clientProfile());oldAccounts.resolve([clientAccount('Private')]);await pause(1)})
    assert.doesNotMatch(renderedText(ui.renderer),/Name0|phone0|Private/)
    assert.match(renderedText(ui.renderer),/denied/)
})

for(const enabled of [true,false]) test(`managed polling ${enabled} selects the central policy or the legacy message timer`,async t=>{
    const ui=provider(t,{}, {enabled,jitter:0});let reads=0
    const {useConversationMessages}=ui.load('useConversationMessages.ts',{
        '../../api/messageApi':{getConversationMessages:async()=>{reads++;return page('current')}},
    })
    function Probe(){useConversationMessages('C');return null}
    t.mock.timers.enable({apis:['setTimeout','Date']})
    await ui.mount(Probe)
    await act(async()=>{ui.stream.onState('open');await ui.stream.onReady({},new AbortController().signal)})
    const current=reads
    await act(async()=>{t.mock.timers.tick(5000);for(let i=0;i<15;i++)await Promise.resolve()})
    assert.equal(reads,current+(enabled?0:1));assert.ok(current>=1)
})

test('the real provider pauses queued HTTP admission while hidden and resumes it on return',async t=>{
    const ui=provider(t);let scope,starts=0
    function Probe(){scope=ui.scopeModule.useUiScope();return null}
    await ui.mount(Probe)
    await act(async()=>{document.hidden=true;document.dispatchEvent(new Event('visibilitychange'))})
    const pending=scope.requestBudget.fetch('/queued',{},async()=>{starts++;return new Response(null,{status:204})}).catch(error=>error)
    await act(async()=>{await Promise.resolve()})
    assert.equal(starts,0);assert.equal(scope.requestBudget.snapshot().queued,1)
    await act(async()=>{document.hidden=false;document.dispatchEvent(new Event('visibilitychange'));await pending})
    assert.equal(starts,1);assert.equal(scope.requestBudget.snapshot().queued,0)
})

// Review regressions assert the recovery contract, not the currently broken behavior.
const flushRecovery=async()=>{for(let i=0;i<80;i++)await Promise.resolve()}
const quietRecovery={jitter:0,requestSpacingMs:0,fallbackMs:120000,fallbackMaxMs:120000,controlMs:240000}

test('reconnected stream.ready preserves Retry-After while healthy subscriptions still reconcile',async t=>{
    t.mock.timers.enable({apis:['setTimeout','Date']})
    const ui=provider(t,{},quietRecovery)
    let limitedReads=0,healthyReads=0
    function Probe(){
        const scope=ui.scopeModule.useUiScope()
        React.useEffect(()=>{
            const limited=scope.owner.subscribe(()=>true,async()=>{
                limitedReads++
                throw Object.assign(new Error('rate limited'),{status:429,retryAfterMs:60000})
            })
            const healthy=scope.owner.subscribe(()=>true,async()=>{healthyReads++})
            return()=>{limited();healthy()}
        },[scope])
        return null
    }
    await ui.mount(Probe)
    for(let reconnect=0;reconnect<5;reconnect++){
        await act(async()=>{
            ui.stream.onState('open')
            assert.equal(await ui.stream.onReady({},new AbortController().signal),false)
        })
    }
    assert.equal(healthyReads,5,'A failed resource must not prevent healthy resources from refreshing')
    assert.equal(limitedReads,1,'Reconnect must not override a resource Retry-After deadline')
    await act(async()=>{t.mock.timers.tick(59999);await flushRecovery()})
    assert.equal(limitedReads,1)
    await act(async()=>{t.mock.timers.tick(1);await flushRecovery()})
    assert.equal(limitedReads,2,'Retry resumes when the server delay expires')
})

for(const status of [429,503]) test(`message reconciliation retains HTTP ${status} Retry-After through the real API and hook`,async t=>{
    t.mock.timers.enable({apis:['setTimeout','Date']})
    const budgetModule=load('../../api/events/requestBudget.ts')
    const {apiFetch}=load('../../api/apiClient.ts',{
        './events/requestBudget':budgetModule,'../auth/authStorage':{getAccessToken:()=> 'A'},
    })
    const messageApi=load('../../api/messageApi.ts',{
        './apiClient':{apiFetch},'./requestBudget':budgetModule,
    })
    const ui=provider(t,{'./requestBudget':budgetModule},quietRecovery)
    let reads=0,state
    t.mock.method(global,'fetch',async()=>{
        reads++
        return reads===2
            ?new Response(JSON.stringify({message:'rate limited'}),{status,headers:{'Retry-After':'60'}})
            :new Response(JSON.stringify(page('current')))
    })
    const {useConversationMessages}=ui.load('useConversationMessages.ts',{'../../api/messageApi':messageApi})
    function Probe(){state=useConversationMessages('C');return null}
    await ui.mount(Probe)
    assert.equal(reads,1);assert.equal(state.messages[0].id,'current')
    await act(async()=>{
        ui.stream.onState('open')
        assert.equal(await ui.stream.onReady({},new AbortController().signal),false)
    })
    assert.equal(reads,2)
    await act(async()=>{t.mock.timers.tick(1000);await flushRecovery()})
    assert.equal(reads,2,'The hook must retain the server delay instead of retrying after the default second')
    await act(async()=>{t.mock.timers.tick(58999);await flushRecovery()})
    assert.equal(reads,2)
    await act(async()=>{t.mock.timers.tick(1);await flushRecovery()})
    assert.equal(reads,3);assert.equal(state.refreshError,null)
})

for(const finalStatus of ['READ','SENT']) test(`old message statuses make durable progress across reconciliation deadlines (${finalStatus})`,async t=>{
    t.mock.timers.enable({apis:['setTimeout','Date']})
    const ui=provider(t,{},quietRecovery)
    const newest=Array.from({length:100},(_,i)=>message('new-'+String(i).padStart(3,'0')))
    const older=Array.from({length:16},(_,i)=>({...message('old-'+i),direction:'OUTBOUND',
        deliveryStatus:'SENT',processingStatus:'PROCESSED',sentAt:'2026-10-21T08:00:00Z'}))
    const completedIds=new Set()
    let state,scope,statusReads=0,ready
    const {useConversationMessages}=ui.load('useConversationMessages.ts',{'../../api/messageApi':{
        getConversationMessages:async(_id,pageNumber)=>({content:pageNumber===0?newest:older,last:pageNumber!==0}),
        getMessage:async(id,signal)=>{
            statusReads++
            await new Promise((resolve,reject)=>{
                const done=()=>{signal.removeEventListener('abort',abort);resolve()}
                const timer=setTimeout(done,10000)
                const abort=()=>{clearTimeout(timer);signal.removeEventListener('abort',abort);reject(signal.reason)}
                signal.addEventListener('abort',abort,{once:true})
                if(signal.aborted)abort()
            })
            completedIds.add(id)
            return {...older.find(item=>item.id===id),deliveryStatus:finalStatus}
        },
    }})
    function Probe(){state=useConversationMessages('C');scope=ui.scopeModule.useUiScope();return null}
    await ui.mount(Probe)
    await act(async()=>{await state.loadOlderMessages()})
    assert.equal(state.messages.length,116,'Use a loaded older page outside the current 100-message page')
    await act(async()=>{
        ui.stream.onState('open')
        ready=ui.stream.onReady({},new AbortController().signal)
        await flushRecovery()
    })
    // Each complete pass takes 40 seconds; the coordinator deadline is the real 30 seconds.
    // Advance through several bounded attempts without waiting for wall-clock time.
    for(let second=0;second<90;second++){
        await act(async()=>{t.mock.timers.tick(1000);await flushRecovery()})
    }
    await ready
    const statuses=state.messages.filter(item=>item.direction==='OUTBOUND')
    assert.equal(statuses.length,16)
    assert.equal(completedIds.size,16,'Unchanged nonterminal statuses must not starve later IDs on retry')
    assert.equal(statuses.filter(item=>item.deliveryStatus===finalStatus).length,16,
        `Completed status responses must survive deadlines and retries (status GETs: ${statusReads})`)
    assert.equal(scope.owner.snapshot().dirty,0,'The resumed finite pass must eventually complete')
    assert.equal(state.messages.filter(item=>item.direction==='INBOUND').length,100)
})
