import {
    apiFetch,
} from './apiClient'

import type {
    OrganizationDto,
} from './types/organization'

export interface UpdateOrganizationRequest {
    name: string
}

export async function getOrganization(
    organizationId: string,
    signal?: AbortSignal,
): Promise<OrganizationDto> {
    const response =
        await apiFetch(
            `/api/organizations/${organizationId}`,
            { signal },
        )

    return response.json()
}

export async function updateOrganization(
    organizationId: string,
    request: UpdateOrganizationRequest,
): Promise<OrganizationDto> {
    const response =
        await apiFetch(
            `/api/organizations/${organizationId}`,
            {
                method: 'PUT',
                body: JSON.stringify(
                    request,
                ),
            },
        )

    return response.json()
}
