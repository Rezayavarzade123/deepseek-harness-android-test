import dev.dsh.harness.ServerTarget;

/** Plain-JVM checks for the address parsing rules in ServerTarget. */
public final class ServerTargetTest {

    private static int failures = 0;
    private static int checks = 0;

    public static void main(String[] args) {
        expect("bare host:port", "192.168.1.20:3080", null, "http://192.168.1.20:3080/", null);
        expect("bare host gets the dsh port", "192.168.1.20", null, "http://192.168.1.20:3080/", null);
        expect("token from the printed URL", "http://192.168.1.20:3080/?token=abc123", null,
                "http://192.168.1.20:3080/", "abc123");
        expect("tokenized URL with whitespace", "  http://10.0.0.4:3080/?token=tok  ", null,
                "http://10.0.0.4:3080/", "tok");
        expect("https behind a mount prefix", "https://app.example/ui/?token=xyz", null,
                "https://app.example/ui/", "xyz");
        expect("https default port stays implicit", "https://app.example", null,
                "https://app.example/", null);
        expect("explicit token wins", "http://10.0.0.4:3080/?token=old", "new",
                "http://10.0.0.4:3080/", "new");
        expect("hostname without a scheme", "dsh.example.com", null,
                "http://dsh.example.com:3080/", null);
        expect("path gains a trailing slash", "http://10.0.0.4:3080/ui", null,
                "http://10.0.0.4:3080/ui/", null);
        expect("non-token query is ignored", "http://10.0.0.4:3080/?foo=1", null,
                "http://10.0.0.4:3080/", null);
        expect("ipv6 literal", "http://[fd00::1]:3080/?token=v6", null,
                "http://[fd00::1]:3080/", "v6");
        expect("token percent-decoding", "http://10.0.0.4:3080/?token=a%2Bb", null,
                "http://10.0.0.4:3080/", "a+b");
        expect("underscore host falls back to the authority", "http://my_box:3080/", null,
                "http://my_box:3080/", null);

        expectNull("empty input", "");
        expectNull("blank input", "   ");
        expectNull("scheme without a host", "http://");
        expectNull("unsupported scheme", "ftp://10.0.0.4/");
        expectNull("missing host in token url", "http:///?token=x");

        ServerTarget target = ServerTarget.parse("192.168.1.20:3080", "a b&c");
        check("token is percent-encoded in the login URL",
                "http://192.168.1.20:3080/?token=a%20b%26c".equals(target.tokenUrl()));
        check("base URL carries no token", "http://192.168.1.20:3080/".equals(target.baseUrl()));
        check("display drops the root path", "192.168.1.20:3080".equals(target.display()));
        check("a tokenless target loads the base URL",
                "http://192.168.1.20:3080/".equals(target.withToken(null).tokenUrl()));
        check("hasToken reports the token", target.withToken(null).hasToken() == false);

        System.out.println(checks + " checks, " + failures + " failures");
        if (failures > 0) {
            System.exit(1);
        }
    }

    private static void expect(String name, String input, String token, String baseUrl, String expectedToken) {
        ServerTarget target = ServerTarget.parse(input, token);
        checks++;
        if (target == null) {
            fail(name, "expected a target, got null");
            return;
        }
        if (!baseUrl.equals(target.baseUrl())) {
            fail(name, "baseUrl " + target.baseUrl() + " != " + baseUrl);
        }
        if (expectedToken == null) {
            if (target.token != null) {
                fail(name, "token " + target.token + " != null");
            }
        } else if (!expectedToken.equals(target.token)) {
            fail(name, "token " + target.token + " != " + expectedToken);
        }
    }

    private static void expectNull(String name, String input) {
        checks++;
        if (ServerTarget.parse(input, null) != null) {
            fail(name, "expected null");
        }
    }

    private static void check(String name, boolean condition) {
        checks++;
        if (!condition) {
            fail(name, "condition is false");
        }
    }

    private static void fail(String name, String detail) {
        failures++;
        System.out.println("FAIL " + name + ": " + detail);
    }
}
