/*
 * *****************************************************************************
 *      Cloud Foundry
 *      Copyright (c) [2009-2015] Pivotal Software, Inc. All Rights Reserved.
 *      This product is licensed to you under the Apache License, Version 2.0 (the "License").
 *      You may not use this product except in compliance with the License.
 *
 *      This product includes a number of subcomponents with
 *      separate copyright notices and license terms. Your use of these
 *      subcomponents is subject to the terms and conditions of the
 *      subcomponent's license, as noted in the LICENSE file.
 * *****************************************************************************
 */

package org.cloudfoundry.identity.uaa.oauth.token;

import org.cloudfoundry.identity.uaa.authentication.Origin;
import org.cloudfoundry.identity.uaa.authentication.UaaAuthentication;
import org.cloudfoundry.identity.uaa.authentication.UaaAuthenticationDetails;
import org.cloudfoundry.identity.uaa.authentication.UaaPrincipal;
import org.cloudfoundry.identity.uaa.test.JdbcTestBase;
import org.cloudfoundry.identity.uaa.util.UaaStringUtils;
import org.cloudfoundry.identity.uaa.zone.IdentityZone;
import org.junit.Before;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.common.exceptions.InvalidGrantException;
import org.springframework.security.oauth2.common.util.OAuth2Utils;
import org.springframework.security.oauth2.provider.OAuth2Authentication;
import org.springframework.security.oauth2.provider.TokenRequest;
import org.springframework.security.oauth2.provider.client.BaseClientDetails;
import org.springframework.security.oauth2.provider.code.JdbcAuthorizationCodeServices;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class UaaTokenStoreTests extends JdbcTestBase {

    private UaaTokenStore store;
    private JdbcAuthorizationCodeServices legacyCodeServices;
    private OAuth2Authentication clientAuthentication;
    private OAuth2Authentication usernamePasswordAuthentication;
    private OAuth2Authentication uaaAuthentication;
    public static final String LONG_CLIENT_ID = "a-client-id-that-is-longer-than-thirty-six-characters-but-less-than-two-hundred-fifty-five-characters-wow-two-hundred-fifty-five-characters-is-actually-a-very-long-client-id-and-we-hope-that-size-limit-should-be-sufficient-for-any-reasonable-application";

    private UaaPrincipal principal = new UaaPrincipal("userid","username","username@test.org", Origin.UAA, null, IdentityZone.getUaa().getId());

    @Before
    public void createTokenStore() throws Exception {
        jdbcTemplate.update("delete from oauth_code");

        List<GrantedAuthority> userAuthorities = Arrays.<GrantedAuthority>asList(new SimpleGrantedAuthority("openid"));

        store = new UaaTokenStore(dataSource);
        legacyCodeServices = new JdbcAuthorizationCodeServices(dataSource);
        BaseClientDetails client = new BaseClientDetails("clientid", null, "openid","client_credentials,password", "oauth.login", null);
        Map<String,String> parameters = new HashMap<>();
        parameters.put(OAuth2Utils.CLIENT_ID, client.getClientId());

        TokenRequest clientRequest = new TokenRequest(new HashMap<>(parameters), client.getClientId(), UaaStringUtils.getStringsFromAuthorities(client.getAuthorities()), "client_credentials");
        clientAuthentication = new OAuth2Authentication(clientRequest.createOAuth2Request(client), null);

        parameters.put("scope","openid");
        parameters.put("grant_type","password");
        UsernamePasswordAuthenticationToken usernamePasswordAuthenticationToken = new UsernamePasswordAuthenticationToken(principal,null,userAuthorities);

        clientRequest = new TokenRequest(new HashMap<>(parameters), client.getClientId(), client.getScope(), "password");
        usernamePasswordAuthentication = new OAuth2Authentication(clientRequest.createOAuth2Request(client), usernamePasswordAuthenticationToken);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");

        UaaAuthentication authentication = new UaaAuthentication(principal, userAuthorities, new UaaAuthenticationDetails(request));
        uaaAuthentication = new OAuth2Authentication(clientRequest.createOAuth2Request(client), authentication);

    }

    @Test
    public void test_ConsumeClientCredentials_From_OldStore() throws  Exception {
        String code = legacyCodeServices.createAuthorizationCode(clientAuthentication);
        assertEquals(1, jdbcTemplate.queryForInt("SELECT count(*) FROM oauth_code WHERE code = ?", code));
        OAuth2Authentication authentication = store.consumeAuthorizationCode(code);
        assertNotNull(authentication);
        assertTrue(authentication.isClientOnly());
        assertEquals(0, jdbcTemplate.queryForInt("SELECT count(*) FROM oauth_code WHERE code = ?", code));
    }

    @Test
    public void testStoreToken_ClientCredentials() throws Exception {
        String code = store.createAuthorizationCode(clientAuthentication);
        assertEquals(1, jdbcTemplate.queryForInt("SELECT count(*) FROM oauth_code WHERE code = ?", code));
        assertNotNull(code);
    }

    @Test
    public void testStoreToken_PasswordGrant_UsernamePasswordAuthentication() throws Exception {
        String code = store.createAuthorizationCode(usernamePasswordAuthentication);
        assertEquals(1, jdbcTemplate.queryForInt("SELECT count(*) FROM oauth_code WHERE code = ?", code));
        assertNotNull(code);
    }

    @Test
    public void testStoreToken_PasswordGrant_UaaAuthentication() throws Exception {
        String code = store.createAuthorizationCode(uaaAuthentication);
        assertEquals(1, jdbcTemplate.queryForInt("SELECT count(*) FROM oauth_code WHERE code = ?", code));
        assertNotNull(code);
    }

    @Test
    public void testRetrieveToken() throws Exception {
        String code = store.createAuthorizationCode(clientAuthentication);
        assertEquals(1, jdbcTemplate.queryForInt("SELECT count(*) FROM oauth_code WHERE code = ?", code));
        OAuth2Authentication authentication = store.consumeAuthorizationCode(code);
        assertEquals(0, jdbcTemplate.queryForInt("SELECT count(*) FROM oauth_code WHERE code = ?", code));
        assertNotNull(authentication);

        code = store.createAuthorizationCode(usernamePasswordAuthentication);
        assertEquals(1, jdbcTemplate.queryForInt("SELECT count(*) FROM oauth_code WHERE code = ?", code));
        authentication = store.consumeAuthorizationCode(code);
        assertEquals(0, jdbcTemplate.queryForInt("SELECT count(*) FROM oauth_code WHERE code = ?", code));
        assertNotNull(authentication);

        code = store.createAuthorizationCode(uaaAuthentication);
        assertEquals(1, jdbcTemplate.queryForInt("SELECT count(*) FROM oauth_code WHERE code = ?", code));
        authentication = store.consumeAuthorizationCode(code);
        assertEquals(0, jdbcTemplate.queryForInt("SELECT count(*) FROM oauth_code WHERE code = ?", code));
        assertNotNull(authentication);
    }

    @Test(expected = InvalidGrantException.class)
    public void testRetrieve_Expired_Token() throws Exception {
        String code = store.createAuthorizationCode(clientAuthentication);
        assertEquals(1, jdbcTemplate.queryForInt("SELECT count(*) FROM oauth_code WHERE code = ?", code));
        jdbcTemplate.update("update oauth_code set expiresat = 1");
        store.consumeAuthorizationCode(code);
    }

    @Test(expected = InvalidGrantException.class)
    public void testRetrieve_Non_Existent_Token() throws Exception {
        String code = store.createAuthorizationCode(clientAuthentication);
        assertEquals(1, jdbcTemplate.queryForInt("SELECT count(*) FROM oauth_code WHERE code = ?", code));
        store.consumeAuthorizationCode("non-existent");
    }

    @Test
    public void testCleanUpExpiredTokensBasedOnExpiresField() throws Exception {
        int count = 10;
        String lastCode = null;
        for (int i=0; i<count; i++) {
            lastCode = store.createAuthorizationCode(clientAuthentication);
        }
        assertEquals(count, jdbcTemplate.queryForInt("SELECT count(*) FROM oauth_code"));

        jdbcTemplate.update("UPDATE oauth_code SET expiresat = ?", System.currentTimeMillis() - 60000);

        try {
            store.consumeAuthorizationCode(lastCode);
            fail();
        } catch (InvalidGrantException e) {
        }
        assertEquals(0, jdbcTemplate.queryForInt("SELECT count(*) FROM oauth_code"));

    }

    @Test
    public void testCleanUpLegacyCodes_Codes_Without_ExpiresAt_After_3_Days() throws Exception {
        int count = 10;
        long oneday = 1000 * 60 * 60 * 24;
        for (int i=0; i<count; i++) {
            legacyCodeServices.createAuthorizationCode(clientAuthentication);
        }
        assertEquals(count, jdbcTemplate.queryForInt("SELECT count(*) FROM oauth_code"));
        jdbcTemplate.update("UPDATE oauth_code SET created = ?", new Timestamp(System.currentTimeMillis() - (2 * oneday)));
        try {
            store.consumeAuthorizationCode("non-existent");
            fail();
        } catch (InvalidGrantException e) {}
        assertEquals(count, jdbcTemplate.queryForInt("SELECT count(*) FROM oauth_code"));
        jdbcTemplate.update("UPDATE oauth_code SET created = ?", new Timestamp(System.currentTimeMillis() - (4 * oneday)));
        try {
            store.consumeAuthorizationCode("non-existent");
            fail();
        } catch (InvalidGrantException e) {}
        assertEquals(0, jdbcTemplate.queryForInt("SELECT count(*) FROM oauth_code"));
    }

    @Test
    public void testExpiresAtOnCode() {
        UaaTokenStore.TokenCode code = store.createTokenCode("code", "userid", "clientid", System.currentTimeMillis() - 1000, new Timestamp(System.currentTimeMillis()), new byte[0]);
        assertTrue(code.isExpired());
    }

    @Test
    public void testExpiresAtOnCreated() {
        UaaTokenStore.TokenCode code = store.createTokenCode("code","userid","clientid",0, new Timestamp(System.currentTimeMillis()), new byte[0]);
        assertFalse(code.isExpired());

        code = store.createTokenCode("code","userid","clientid",0, new Timestamp(System.currentTimeMillis()-(2*store.getExpirationTime())), new byte[0]);
        assertTrue(code.isExpired());
    }

    @Test
    public void testCleanUpUnusedOldTokens_MySQL_In_Another_Timezone() throws Exception {
        //only run tests for MySQL for now.
        Optional<String> dbProfile = Arrays.stream(environment.getActiveProfiles()).filter(s -> s.contains("sql")).findFirst();
        String db = dbProfile.isPresent() ? dbProfile.get() : "hsqldb";

        Connection con = dataSource.getConnection();
        try {
            Connection dontClose = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                                                                       new Class[]{Connection.class},
                                                                       new DontCloseConnection(con));

            SameConnectionDataSource sameConnectionDataSource = new SameConnectionDataSource(dontClose);
            JdbcTemplate template = new JdbcTemplate(sameConnectionDataSource);
            switch (db) {
                case "mysql" :
                    template.update("SET @@session.time_zone='-11:00'");
                    break;
                case "postgresql" :
                    template.update("SET TIME ZONE -11");
                    break;
                case "hsqldb" :
                    template.update("SET TIME ZONE INTERVAL '-11:00' HOUR TO MINUTE");
                    break;
                default:
                    fail("Unknown DB profile:"+db);
            }

            store = new UaaTokenStore(sameConnectionDataSource);
            legacyCodeServices = new JdbcAuthorizationCodeServices(sameConnectionDataSource);
            int count = 10;
            String lastCode = null;
            for (int i=0; i<count; i++) {
                lastCode = legacyCodeServices.createAuthorizationCode(clientAuthentication);
            }

            assertEquals(count, template.queryForInt("SELECT count(*) FROM oauth_code"));
            try { store.consumeAuthorizationCode(lastCode); } catch (Exception ignore) {}
            assertEquals(count-1, template.queryForInt("SELECT count(*) FROM oauth_code"));
        } finally {
            con.close();
            store = new UaaTokenStore(dataSource);
            legacyCodeServices = new JdbcAuthorizationCodeServices(dataSource);
        }
    }



    public class SameConnectionDataSource implements DataSource {
        private final Connection con;

        public SameConnectionDataSource(Connection con) {
            this.con = con;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return con;
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return con;
        }

        @Override
        public PrintWriter getLogWriter() throws SQLException {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) throws SQLException {

        }

        @Override
        public void setLoginTimeout(int seconds) throws SQLException {

        }

        @Override
        public int getLoginTimeout() throws SQLException {
            return 0;
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            return null;
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            return null;
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) throws SQLException {
            return false;
        }
    }

    public class DontCloseConnection implements InvocationHandler {
        public static final String CLOSE_VAL = "close";
        private final Connection con;

        public DontCloseConnection(Connection con) {
            this.con = con;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (CLOSE_VAL.equals(method.getName())) {
                return null;
            } else {
                return method.invoke(con, args);
            }
        }
    }
}
