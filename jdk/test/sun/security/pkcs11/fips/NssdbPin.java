/*
 * Copyright (c) 2022, Red Hat, Inc.
 *
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.Files;
import java.nio.file.FileVisitResult;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.security.KeyStore;
import java.security.Provider;
import java.security.Security;
import java.util.Arrays;
import java.util.function.Consumer;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/*
 * @test
 * @bug 9999999
 * @summary
 *   Test that the fips.nssdb.path and fips.nssdb.pin properties can be used
 *   for a successful login into an NSS DB. Some additional unitary testing
 *   is then peformed. This test depends on NSS modutil and must be run in
 *   FIPS mode (the SunPKCS11-NSS-FIPS security provider has to be available).
 * @modules jdk.crypto.cryptoki/sun.security.pkcs11:+open
 *          java.base/jdk.internal.misc
 * @library /java/security/testlibrary
 * @requires (jdk.version.major >= 8)
 * @run main/othervm/timeout=600 NssdbPin
 * @author Martin Balao (mbalao@redhat.com)
 */

public final class NssdbPin {

    // Public properties and names
    private static final String FIPS_NSSDB_PATH_PROP = "fips.nssdb.path";
    private static final String FIPS_NSSDB_PIN_PROP = "fips.nssdb.pin";
    private static final String FIPS_PROVIDER_NAME = "SunPKCS11-NSS-FIPS";
    private static final String NSSDB_TOKEN_NAME =
            "NSS FIPS 140-2 Certificate DB";

    // Data to be tested
    private static final String[] PINS_TO_TEST =
            new String[] {
                    "",
                    "1234567890abcdef1234567890ABCDEF" +
                            new String(new char[] {'\uA4F7'})};
    private static enum PropType { SYSTEM, SECURITY }
    private static enum LoginType { IMPLICIT, EXPLICIT }

    // Internal test fields
    private static final boolean DEBUG = true;
    private static class TestContext {
        String pin;
        PropType propType;
        Path workspace;
        String nssdbPath;
        Path nssdbPinFile;
        LoginType loginType;
        TestContext(String pin, Path workspace) {
            this.pin = pin;
            this.workspace = workspace;
            this.nssdbPath = "sql:" + workspace;
            this.loginType = LoginType.IMPLICIT;
        }
    }

    public static void main(String[] args) throws Throwable {
        if (args.length > 0) {
            // Executed by a child process.
            mainChild(args[0], args[1], LoginType.valueOf(args[2]));
        } else {
            // Executed by the parent process.
            mainLauncher();
            // Test defaults
            mainChild("sql:/etc/pki/nssdb", "", LoginType.IMPLICIT);
            System.out.println("TEST PASS - OK");
        }
    }

    private static void mainChild(String expectedPath, String expectedPin,
            LoginType loginType) throws Throwable {
        if (DEBUG) {
            for (String prop : Arrays.asList(FIPS_NSSDB_PATH_PROP,
                    FIPS_NSSDB_PIN_PROP)) {
                System.out.println(prop + " (System): " +
                        System.getProperty(prop));
                System.out.println(prop + " (Security): " +
                        Security.getProperty(prop));
            }
        }

        /*
         * Functional cross-test against an NSS DB generated by modutil
         * with the same PIN. Check that we can perform a crypto operation
         * that requires a login. The login might be explicit or implicit.
         */
        Provider p = Security.getProvider(FIPS_PROVIDER_NAME);
        if (DEBUG) {
            System.out.println(FIPS_PROVIDER_NAME + ": " + p);
        }
        if (p == null) {
            throw new Exception(FIPS_PROVIDER_NAME + " initialization failed.");
        }
        if (DEBUG) {
            System.out.println("Login type: " + loginType.name());
        }
        if (loginType == LoginType.EXPLICIT) {
            // Do the expansion to account for truncation, so C_Login in
            // the NSS Software Token gets a UTF-8 encoded PIN.
            byte[] pinUtf8 = expectedPin.getBytes(StandardCharsets.UTF_8);
            char[] pinChar = new char[pinUtf8.length];
            for (int i = 0; i < pinChar.length; i++) {
                pinChar[i] = (char)(pinUtf8[i] & 0xFF);
            }
            KeyStore.getInstance("PKCS11", p).load(null, pinChar);
            if (DEBUG) {
                System.out.println("Explicit login succeeded.");
            }
        }
        if (DEBUG) {
            System.out.println("Trying a crypto operation...");
        }
        final int blockSize = 16;
        Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding", p);
        cipher.init(Cipher.ENCRYPT_MODE,
                new SecretKeySpec(new byte[blockSize], "AES"));
        if (cipher.doFinal(new byte[blockSize]).length != blockSize) {
            throw new Exception("Could not perform a crypto operation.");
        }
        if (DEBUG) {
            if (loginType == LoginType.IMPLICIT) {
                System.out.println("Implicit login succeeded.");
            }
            System.out.println("Crypto operation after login succeeded.");
        }

        if (loginType == LoginType.IMPLICIT) {
            /*
             * Additional unitary testing. Expected to succeed at this point.
             */
            if (DEBUG) {
                System.out.println("Trying unitary test...");
            }
            String sysPathProp = System.getProperty(FIPS_NSSDB_PATH_PROP);
            if (DEBUG) {
                System.out.println("Path value (as a System property): " +
                        sysPathProp);
            }
            if (!expectedPath.equals(sysPathProp)) {
                throw new Exception("Path is different than expected: " +
                        sysPathProp + " (actual) vs " + expectedPath +
                        " (expected).");
            }
            Class<?> c = Class
                    .forName("sun.security.pkcs11.FIPSTokenLoginHandler");
            Method m = c.getDeclaredMethod("getFipsNssdbPin");
            m.setAccessible(true);
            char[] pinChar = (char[]) m.invoke(c);
            byte[] pinUtf8 = new byte[pinChar.length];
            for (int i = 0; i < pinUtf8.length; i++) {
                pinUtf8[i] = (byte) pinChar[i];
            }
            String pin = new String(pinUtf8, StandardCharsets.UTF_8);
            if (!pin.equals(expectedPin)) {
                throw new Exception("PIN is different than expected: " + pin +
                         " (actual) vs " + expectedPin + " (expected).");
            }
            if (DEBUG) {
                System.out.println("PIN value: " + pin);
                System.out.println("Unitary test succeeded.");
            }
        }
    }

    private static void mainLauncher() throws Throwable {
        for (String pin : PINS_TO_TEST) {
            Path workspace = Files.createTempDirectory(null);
            try {
                TestContext ctx = new TestContext(pin, workspace);
                createNSSDB(ctx);
                for (PropType propType : PropType.values()) {
                    ctx.propType = propType;
                    pinLauncher(ctx);
                    envLauncher(ctx);
                    fileLauncher(ctx);
                }
                explicitLoginLauncher(ctx);
            } finally {
                deleteDir(workspace);
            }
        }
    }

    private static void pinLauncher(TestContext ctx) throws Throwable {
        launchTest(p -> {}, "pin:" + ctx.pin, ctx);
    }

    private static void envLauncher(TestContext ctx) throws Throwable {
        final String NSSDB_PIN_ENV_VAR = "NSSDB_PIN_ENV_VAR";
        launchTest(p -> p.env(NSSDB_PIN_ENV_VAR, ctx.pin),
                "env:" + NSSDB_PIN_ENV_VAR, ctx);
    }

    private static void fileLauncher(TestContext ctx) throws Throwable {
        launchTest(p -> {}, "file:" + ctx.nssdbPinFile, ctx);
    }

    private static void explicitLoginLauncher(TestContext ctx)
            throws Throwable {
        ctx.loginType = LoginType.EXPLICIT;
        ctx.propType = PropType.SYSTEM;
        launchTest(p -> {}, "Invalid PIN, must be ignored", ctx);
    }

    private static void launchTest(Consumer<Proc> procCb, String pinPropVal,
            TestContext ctx) throws Throwable {
        if (DEBUG) {
            System.out.println("Launching JVM with " + FIPS_NSSDB_PATH_PROP +
                    "=" + ctx.workspace + " and " + FIPS_NSSDB_PIN_PROP +
                    "=" + pinPropVal);
        }
        Proc p = Proc.create(NssdbPin.class.getName())
                .args(ctx.nssdbPath, ctx.pin, ctx.loginType.name());
        if (ctx.propType == PropType.SYSTEM) {
            p.prop(FIPS_NSSDB_PATH_PROP, ctx.nssdbPath);
            p.prop(FIPS_NSSDB_PIN_PROP, pinPropVal);
            // Make sure that Security properties defaults are not used.
            p.secprop(FIPS_NSSDB_PATH_PROP, "");
            p.secprop(FIPS_NSSDB_PIN_PROP, "");
        } else if (ctx.propType == PropType.SECURITY) {
            p.secprop(FIPS_NSSDB_PATH_PROP, ctx.nssdbPath);
            pinPropVal = escapeForPropsFile(pinPropVal);
            p.secprop(FIPS_NSSDB_PIN_PROP, pinPropVal);
        } else {
            throw new Exception("Unsupported property type.");
        }
        if (DEBUG) {
            p.inheritIO();
            p.prop("java.security.debug", "sunpkcs11");
            p.debug(NssdbPin.class.getName());

            // Need the launched process to connect to a debugger?
            //System.setProperty("test.vm.opts", "-Xdebug -Xrunjdwp:" +
            //         "transport=dt_socket,address=localhost:8000,suspend=y");
        } else {
            p.nodump();
        }
        procCb.accept(p);
        p.start().waitFor(0);
    }

    private static String escapeForPropsFile(String str) throws Throwable {
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < str.length(); i++) {
            int cp = str.codePointAt(i);
            if (Character.UnicodeBlock.of(cp)
                    == Character.UnicodeBlock.BASIC_LATIN) {
                sb.append(Character.toChars(cp));
            } else {
                sb.append("\\u").append(String.format("%04X", cp));
            }
        }
        return sb.toString();
    }

    private static void createNSSDB(TestContext ctx) throws Throwable {
        ProcessBuilder pb = getModutilPB(ctx, Arrays.asList("-create"), null);
        if (DEBUG) {
            System.out.println("Creating an NSS DB in " + ctx.workspace +
                    "...");
            System.out.println("cmd: " + String.join(" ", pb.command()));
        }
        if (pb.start().waitFor() != 0) {
            throw new Exception("NSS DB creation failed.");
        }
        generatePinFile(ctx);
        pb = getModutilPB(ctx, Arrays.asList("-changepw", NSSDB_TOKEN_NAME),
                Arrays.asList("-newpwfile", ctx.nssdbPinFile.toString()));
        if (DEBUG) {
            System.out.println("NSS DB created.");
            System.out.println("Changing NSS DB PIN...");
            System.out.println("cmd: " + String.join(" ", pb.command()));
        }
        if (pb.start().waitFor() != 0) {
            throw new Exception("NSS DB PIN change failed.");
        }
        if (DEBUG) {
            System.out.println("NSS DB PIN changed.");
        }
    }

    private static ProcessBuilder getModutilPB(TestContext ctx,
            List<String> opts, List<String> args) throws Throwable {
        ProcessBuilder pb = new ProcessBuilder("modutil", "-force");
        List<String> pbCommand = pb.command();
        if (opts != null) {
            pbCommand.addAll(opts);
        }
        if (args != null) {
            pbCommand.addAll(args);
        }
        pbCommand.add("-dbdir");
        pbCommand.add(ctx.nssdbPath);
        if (DEBUG) {
            pb.inheritIO();
        } else {
            pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        }
        return pb;
    }

    private static void generatePinFile(TestContext ctx) throws Throwable {
        ctx.nssdbPinFile = Files.createTempFile(ctx.workspace, null, null);
        try (BufferedWriter bw = Files.newBufferedWriter(ctx.nssdbPinFile,
                new OpenOption[] {})) {
            bw.write(ctx.pin);
            bw.write(System.lineSeparator());
            bw.write("2nd line with garbage");
        }
    }

    private static void deleteDir(final Path directory) throws IOException {
        Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file,
                    BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir,
                    IOException exc) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
