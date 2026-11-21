package kit.penny.clientbus.server.crm;

import jakarta.persistence.LockModeType;
import kit.penny.clientbus.common.dto.crm.CrmClientDto;
import kit.penny.clientbus.crm.api.*;
import kit.penny.clientbus.server.persistence.entity.ClientEntity;
import kit.penny.clientbus.server.persistence.entity.crm.CrmClientEntity;
import kit.penny.clientbus.server.notification.outbox.UiDomainEvents;
import kit.penny.clientbus.server.storage.IAttachmentStorage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Explicit, one-shot photo replacement. No automatic external retry, including after a lost response. */
@Service
public class CrmClientAvatarTransfer {
    private final CrmStore store;private final CrmRegistry registry;private final CrmSecretCipher cipher;
    private final IAttachmentStorage storage;private final UiDomainEvents events;private final TransactionTemplate tx;
    private final Set<UUID> active=ConcurrentHashMap.newKeySet();
    public record Request(UUID clientId,Long expectedVersion,String sourceAvatarUrl) {}
    record Snapshot(CrmContext context,CrmCredentials credentials,UUID source,UUID actor,String key) {}
    public CrmClientAvatarTransfer(CrmStore store,CrmRegistry registry,CrmSecretCipher cipher,@Qualifier("attachmentStorage") IAttachmentStorage storage,UiDomainEvents events,PlatformTransactionManager manager){
        this.store=store;this.registry=registry;this.cipher=cipher;this.storage=storage;this.events=events;tx=new TransactionTemplate(manager);
    }
    public CrmClientDto transfer(UUID id,Request request){
        store.storage.requireReady();
        if(request==null || request.clientId()==null || request.expectedVersion()==null || request.expectedVersion()<0 || request.sourceAvatarUrl()==null)throw CrmException.invalid();
        if(!active.add(id))throw CrmException.conflict("CRM_CONNECTION_BUSY");
        try{
            var snapshot=tx.execute(s->{
                var row=store.em.find(CrmClientEntity.class,id);if(row==null)throw CrmStore.notFound();
                var project=store.project(row.getProject().getId(),store.guard.organization());
                var core=store.em.find(ClientEntity.class,request.clientId(),LockModeType.PESSIMISTIC_WRITE);if(core==null || !core.getOrganization().getId().equals(project.getOrganization().getId()))throw CrmStore.notFound();
                store.lockMappingEmployees(store.guard.actor(),null);store.em.refresh(project.getConnection(),LockModeType.PESSIMISTIC_READ);store.em.refresh(project,LockModeType.PESSIMISTIC_WRITE);store.em.refresh(row,LockModeType.PESSIMISTIC_WRITE);
                store.guard.workspace(project.getWorkspace().getId(),true);CrmStore.requireMutable(project);
                if(!project.isEnabled() || project.getConnection().getStatus()!=kit.penny.clientbus.common.enums.CrmConnectionStatus.CONNECTED || !"DIKIDI".equals(project.getProvider().name()))throw CrmException.conflict("CRM_CONFIGURATION_REQUIRED");
                if(row.getClient()==null || !row.getClient().getId().equals(core.getId()) || row.getVersion()!=request.expectedVersion())throw CrmException.conflict("CRM_VERSION_CONFLICT");
                if(core.getAvatarUrl()==null || core.getAvatarUrl().isBlank())throw new CrmException(409,"CRM_SOURCE_AVATAR_MISSING","У клиента ClientBus нет аватара.");
                if(!core.getAvatarUrl().equals(request.sourceAvatarUrl()))throw CrmException.conflict("CRM_VERSION_CONFLICT");
                store.requireNetwork(project);
                return new Snapshot(store.context(project),store.credentials(project.getConnection()),core.getId(),store.guard.actor(),core.getAvatarUrl());
            });
            byte[] png;
            try(var input=storage.load(snapshot.key())){png=photo(input);}
            catch(IOException | RuntimeException failure){throw new CrmException(409,"CRM_SOURCE_AVATAR_INVALID","Не удалось прочитать фото ClientBus. Поддерживаются JPEG, PNG и GIF размером до 5 МБ.");}
            String url;
            try(var access=snapshot.credentials().open(cipher)){
                String external=tx.execute(s->store.em.find(CrmClientEntity.class,id).getExternalId());
                url=registry.require(snapshot.credentials().provider).transferClientAvatar(access,snapshot.context().externalProjectId(),external,png,Instant.now().plusSeconds(65),()->tx.executeWithoutResult(s->{
                    var core=store.em.find(ClientEntity.class,snapshot.source(),LockModeType.PESSIMISTIC_WRITE);store.lockMappingEmployees(snapshot.actor(),null);
                    store.requireContext(snapshot.context());store.guard.manageWorkspace(snapshot.actor(),snapshot.context().workspaceId());
                    var row=store.em.find(CrmClientEntity.class,id,LockModeType.PESSIMISTIC_WRITE);if(row==null)throw CrmStore.notFound();store.em.refresh(row);
                    if(core==null || !Objects.equals(snapshot.key(),core.getAvatarUrl()) || row.getClient()==null || !snapshot.source().equals(row.getClient().getId()))throw CrmException.conflict("CRM_VERSION_CONFLICT");
                }));
            }catch(CrmFailure failure){
                if(failure.notDispatched())throw CrmStore.failure(failure);
                throw new CrmException(409,"CRM_AVATAR_RESULT_UNKNOWN","Не удалось подтвердить перенос фото. Проверьте аватар в DIKIDI перед новым переносом.");
            }
            var mirrored=storage.store(new ByteArrayInputStream(png),"crm-avatar.png",png.length,"image/png");
            return tx.execute(s->{
                var core=store.em.find(ClientEntity.class,snapshot.source(),LockModeType.PESSIMISTIC_WRITE);store.lockMappingEmployees(snapshot.actor(),null);
                var project=store.em.find(kit.penny.clientbus.server.persistence.entity.crm.CrmProjectEntity.class,snapshot.context().projectId(),LockModeType.PESSIMISTIC_WRITE);
                var row=store.em.find(CrmClientEntity.class,id,LockModeType.PESSIMISTIC_WRITE);if(row==null)throw CrmStore.notFound();store.em.refresh(row);
                var expected=snapshot.context();
                if(core==null || project==null || row==null || row.getClient()==null || !snapshot.source().equals(row.getClient().getId())
                        || !row.getProject().getId().equals(expected.projectId()) || !project.getOrganization().getId().equals(expected.organizationId())
                        || !project.getWorkspace().getId().equals(expected.workspaceId()) || !project.getProvider().name().equals(expected.provider())
                        || !project.getExternalId().equals(expected.externalProjectId()))throw CrmException.conflict("CRM_CONTEXT_CHANGED");
                row.setAvatarUrl(mirrored.storageKey());row.setAvatarOrigin(CrmAvatarUrl.key(url));store.em.flush();
                events.workspace(project.getWorkspace(),"crm.invalidated");return CrmClientService.dto(row);
            });
        }finally{active.remove(id);}
    }
    static byte[] photo(InputStream input)throws IOException{
        byte[] bytes=input.readNBytes(5*1024*1024+1);if(bytes.length>5*1024*1024)throw new IllegalArgumentException();
        try(var imageInput=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))){
            var readers=ImageIO.getImageReaders(imageInput);if(!readers.hasNext())throw new IllegalArgumentException();var reader=readers.next();
            try{reader.setInput(imageInput,true,true);int width=reader.getWidth(0),height=reader.getHeight(0);if(width<1 || height<1 || (long)width*height>16_000_000)throw new IllegalArgumentException();
                var source=reader.read(0);int side=Math.min(width,height);var result=new BufferedImage(512,512,BufferedImage.TYPE_INT_RGB);var graphics=result.createGraphics();
                try{graphics.setColor(Color.WHITE);graphics.fillRect(0,0,512,512);graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);graphics.drawImage(source,0,0,512,512,(width-side)/2,(height-side)/2,(width+side)/2,(height+side)/2,null);}finally{graphics.dispose();}
                var output=new ByteArrayOutputStream();if(!ImageIO.write(result,"png",output))throw new IOException();return output.toByteArray();
            }finally{reader.dispose();}
        }
    }
}
