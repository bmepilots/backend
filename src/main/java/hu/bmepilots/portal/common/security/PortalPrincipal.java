package hu.bmepilots.portal.common.security;

import java.io.Serializable;

public record PortalPrincipal(
    String id, String email, String displayName, String role, long authVersion, long issuedAt)
    implements Serializable {}
