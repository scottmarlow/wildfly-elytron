/*
 * JBoss, Home of Professional Open Source
 * Copyright 2014 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.wildfly.security.auth.provider.ldap;

import java.security.Principal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.NameCallback;
import javax.security.auth.callback.PasswordCallback;
import javax.security.auth.x500.X500Principal;

import org.wildfly.security.auth.principal.NamePrincipal;
import org.wildfly.security.auth.spi.AuthorizationIdentity;
import org.wildfly.security.auth.spi.CredentialSupport;
import org.wildfly.security.auth.spi.RealmIdentity;
import org.wildfly.security.auth.spi.SecurityRealm;
import org.wildfly.security.auth.util.NameRewriter;
import org.wildfly.security.password.Password;
import org.wildfly.security.password.interfaces.ClearPassword;

import static org.wildfly.security._private.ElytronMessages.log;

/**
 * Security realm implementation backed by LDAP.
 *
 * @author <a href="mailto:darran.lofthouse@jboss.com">Darran Lofthouse</a>
 */
class LdapSecurityRealm implements SecurityRealm {

    private final DirContextFactory dirContextFactory;
    private final List<NameRewriter> nameRewriters;
    private final PrincipalMapping principalMapping;

    private final Collection<CredentialLoader> credentialLoaders;

    LdapSecurityRealm(final DirContextFactory dirContextFactory, final List<NameRewriter> nameRewriters,
            final PrincipalMapping principalMapping, final Collection<CredentialLoader> credentialLoaders) {
        this.dirContextFactory = dirContextFactory;
        this.nameRewriters = nameRewriters;
        this.principalMapping = principalMapping;
        this.credentialLoaders = credentialLoaders;
    }

    @Override
    public RealmIdentity createRealmIdentity(String name) {
        for (NameRewriter current : nameRewriters) {
            name = current.rewriteName(name);
        }

        return principalMapping.nameIsDn ? new LdapRealmIdentity(null, name) : new LdapRealmIdentity(name, null);
    }

    @Override
    public RealmIdentity createRealmIdentity(Principal principal) {
        if (principal instanceof NamePrincipal  || principal instanceof X500Principal) {
            return new LdapRealmIdentity(principal);
        }

        return null;
    }

    @Override
    public CredentialSupport getCredentialSupport(Class<?> credentialType) {
        CredentialSupport response = CredentialSupport.UNSUPPORTED;
        if (Password.class.isAssignableFrom(credentialType) == false) {
            return response;
        }

        for (CredentialLoader current : credentialLoaders) {
            CredentialSupport support = current.getCredentialSupport(dirContextFactory, credentialType);
            if (support.isDefinitelyObtainable()) {
                // One claiming it is definitely supported is enough!
                return support;
            }
            if (response.compareTo(support) < 0) {
                response = support;
            }
        }

        return response;
    }

    private class LdapRealmIdentity implements RealmIdentity {

        private boolean loadedNames = false;
        private String simpleName;
        private String distinguishedName;
        private Principal principal;

        private LdapRealmIdentity(final String simpleName, final String distinguishedName) {
            this.simpleName = simpleName;
            this.distinguishedName = distinguishedName;
        }

        private LdapRealmIdentity(final Principal principal) {
            assert principal instanceof NamePrincipal || principal instanceof X500Principal;
            if (principal instanceof NamePrincipal) {
                if (principalMapping.principalUseDn) {
                    distinguishedName = principal.getName();
                } else {
                    simpleName = principal.getName();
                }
            } else {
                distinguishedName = principal.getName();
            }
            this.principal = principal;
        }

        private void loadNames() {
            // TODO - We need a slightly different approach if the name was obtained from a Principal.

            if (loadedNames == false) {
                try {
                    NamePair np = loadNamePair(simpleName, distinguishedName);
                    loadedNames = true;
                    simpleName = np.simpleName;
                    distinguishedName = np.distinguishedName;
                } catch (NamingException e) {
                    // TODO - Log
                }
            }
        }

        @Override
        public Principal getPrincipal() {
            if (principal == null) {
                loadNames();
                if (loadedNames) {
                    if (principalMapping.principalUseDn) {
                        try {
                            principal = new X500Principal(distinguishedName);
                        } catch (IllegalArgumentException ignored) {
                            principal = new NamePrincipal(distinguishedName);
                        }
                    } else {
                        principal = new NamePrincipal(simpleName);
                    }
                }
            }

            return principal;
        }

        @Override
        public CredentialSupport getCredentialSupport(Class<?> credentialType) {
            if (LdapSecurityRealm.this.getCredentialSupport(credentialType) == CredentialSupport.UNSUPPORTED) {
                // If not supported in general then definitely not supported for a specific principal.
                return CredentialSupport.UNSUPPORTED;
            }

            CredentialSupport support = null;

            loadNames();
            for (CredentialLoader current : credentialLoaders) {
                if (current.getCredentialSupport(dirContextFactory, credentialType).mayBeObtainable()) {
                    IdentityCredentialLoader icl = current.forIdentity(dirContextFactory, distinguishedName);

                    CredentialSupport temp = icl.getCredentialSupport(credentialType);
                    if (temp != null && temp.isDefinitelyObtainable()) {
                        // As soon as one claims definite support we know it is supported.
                        return temp;
                    }

                    if (support == null || temp != null && support.compareTo(temp) < 0) {
                        support = temp;
                    }
                }
            }

            if (support == null) {
                return CredentialSupport.UNSUPPORTED;
            }

            return support;
        }

        @Override
        public <C> C getCredential(Class<C> credentialType) {
            if (LdapSecurityRealm.this.getCredentialSupport(credentialType) == CredentialSupport.UNSUPPORTED) {
                // If not supported in general then definitely not supported for a specific principal.
                return null;
            }

            loadNames();
            for (CredentialLoader current : credentialLoaders) {
                if (current.getCredentialSupport(dirContextFactory, credentialType).mayBeObtainable()) {
                    IdentityCredentialLoader icl = current.forIdentity(dirContextFactory, distinguishedName);

                    C credential = icl.getCredential(credentialType);
                    if (credential != null) {
                        return credential;
                    }
                }
            }

            return null;
        }

        @Override
        public AuthorizationIdentity getAuthorizationIdentity() {
            return new AuthorizationIdentity() {
                public Principal getPrincipal() {
                    return principal;
                }

                @Override
                public Set<String> getRoles() {
                    return Collections.emptySet();
                }
            };
        }

        @Override
        public boolean verifyCredential(final Object credential) {
            if (ClearPassword.class.isInstance(credential)) {
                final ClearPassword clearPassword = (ClearPassword) credential;
                DirContext dirContext = null;

                try {
                    // TODO: for not we just create a DirContext using the provided credentials. Need to also support referrals.
                    dirContext = dirContextFactory.obtainDirContext(callbacks -> {
                        for (Callback callback : callbacks) {
                            if (NameCallback.class.isInstance(callback)) {
                                NameCallback nameCallback = (NameCallback) callback;
                                nameCallback.setName(getPrincipal().getName());
                            } else if (PasswordCallback.class.isInstance(callback)) {
                                PasswordCallback nameCallback = (PasswordCallback) callback;
                                nameCallback.setPassword(clearPassword.getPassword());
                            }
                        }
                    }, null);

                    return true;
                } catch (NamingException e) {
                    log.debugf("Credential verification failed.", e);
                } finally {
                    if (dirContext != null) {
                        dirContextFactory.returnContext(dirContext);
                    }
                }
            } else {
                throw new IllegalArgumentException("Unsupported credential type [" + credential.getClass() + "].");
            }

            return false;
        }
    }

    static class PrincipalMapping {

        private final String searchDn;
        private final boolean recursive;
        private final boolean nameIsDn;
        private final boolean principalUseDn;
        private final String nameAttribute;
        private final String dnAttribute;
        private final boolean validatePresence;
        private final boolean reloadPrincipalName;

        public PrincipalMapping(String searchDn, boolean recursive, boolean nameIsDn, boolean principalUseDn,
                String nameAttribute, String dnAttribute, boolean validatePresence, boolean reloadPrincipalName) {
            this.searchDn = searchDn;
            this.recursive = recursive;
            this.nameIsDn = nameIsDn;
            this.principalUseDn = principalUseDn;
            this.nameAttribute = nameAttribute;
            this.dnAttribute = dnAttribute;
            this.validatePresence = validatePresence;
            this.reloadPrincipalName = reloadPrincipalName;
        }

    }

    private NamePair loadNamePair(String simpleName, String distinguishedName) throws NamingException {
        DirContext context = null;
        NamingEnumeration<SearchResult> searchResult = null;
        try {

            if (principalMapping.nameIsDn) {
                if (principalMapping.principalUseDn == false
                        || (principalMapping.reloadPrincipalName || principalMapping.validatePresence)) {
                    context = dirContextFactory.obtainDirContext(null); // TODO - Referral Mode
                    ArrayList<String> requiredAttributes = new ArrayList<String>(2);
                    if (principalMapping.reloadPrincipalName) {
                        requiredAttributes.add(principalMapping.dnAttribute);
                    }
                    if (principalMapping.nameAttribute != null) {
                        requiredAttributes.add(principalMapping.nameAttribute);
                    }

                    Attributes attributes = context.getAttributes(distinguishedName,
                            requiredAttributes.toArray(new String[requiredAttributes.size()]));
                    if (principalMapping.nameAttribute != null) {
                        Attribute nameAttribute = attributes.get(principalMapping.nameAttribute);
                        if (nameAttribute != null) {
                            simpleName = (String) nameAttribute.get();
                        }
                    }

                    if (principalMapping.reloadPrincipalName) {
                        Attribute dnAttribute = attributes.get(principalMapping.dnAttribute);
                        if (dnAttribute != null) {
                            distinguishedName = (String) dnAttribute.get();
                        }
                    }
                }
            } else {
                SearchControls searchControls = new SearchControls();
                searchControls.setSearchScope(principalMapping.recursive ? SearchControls.SUBTREE_SCOPE
                        : SearchControls.ONELEVEL_SCOPE);
                searchControls.setTimeLimit(10000); // TODO - Make Config

                context = dirContextFactory.obtainDirContext(null); // TODO - Referral Mode
                ArrayList<String> requiredAttributes = new ArrayList<String>(2);
                if (principalMapping.reloadPrincipalName) {
                    requiredAttributes.add(principalMapping.nameAttribute);
                }
                if (principalMapping.dnAttribute != null) {
                    requiredAttributes.add(principalMapping.dnAttribute);
                }

                if (requiredAttributes.size() > 0) {
                searchControls.setReturningAttributes(requiredAttributes.toArray(new String[requiredAttributes.size()]));
                }

                Object[] filterArg = new Object[] { simpleName };
                String filter = String.format("(%s={0})", principalMapping.nameAttribute);

                searchResult = context.search(principalMapping.searchDn, filter, filterArg, searchControls);
                if (searchResult.hasMore()) {
                    SearchResult result = searchResult.next();
                    if (searchResult.hasMore()) {
                        throw new NamingException("Search returned too many results.");
                    }

                    Attributes attributes = result.getAttributes();
                    if (principalMapping.dnAttribute != null) {
                        Attribute dn = attributes.get(principalMapping.dnAttribute);
                        if (dn != null) {
                            distinguishedName = (String) dn.get();
                        }
                    }
                    if (distinguishedName == null) {
                        distinguishedName = result.getName()
                                + ("".equals(principalMapping.searchDn) ? "" : "," + principalMapping.searchDn);
                    }
                    if (principalMapping.reloadPrincipalName) {
                        Attribute nameAttribute = attributes.get(principalMapping.nameAttribute);
                        if (nameAttribute != null) {
                            simpleName = (String) nameAttribute.get();
                        }
                    }
                } else {
                    throw new NamingException("Search returned no results.");
                }
            }

            return new NamePair(simpleName, distinguishedName);

        } finally {
            if (searchResult != null) {
                searchResult.close();
            }
            if (context != null) {
                dirContextFactory.returnContext(context);
            }
        }
    }

    private class NamePair {
        private final String simpleName;
        private final String distinguishedName;

        public NamePair(String simpleName, String distinguishedName) {
            this.simpleName = simpleName;
            this.distinguishedName = distinguishedName;
        }

    }

}
