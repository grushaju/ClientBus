export type PushPlatform = 'ANDROID' | 'IOS'
export type PushProviderType = 'FCM' | 'APNS'
export type PushEnvironment = 'PRODUCTION' | 'SANDBOX'
export type PushPermission = 'UNKNOWN' | 'GRANTED' | 'DENIED' | 'PROVISIONAL'
export interface PushInstallationRequest {
    expectedRegistrationVersion: number
    platform: PushPlatform
    provider: PushProviderType
    appId: string
    environment: PushEnvironment
    registrationReference: string
    installationSecret: string
    enabled: boolean
    reportedPermission: PushPermission
}
export interface PushInstallationDto {
    registrationVersion: number
    installationId: string
    bindingVersion: number
    endpointVersion: number
    enabled: boolean
    reportedPermission: PushPermission
    lastSeenAt: string
}
