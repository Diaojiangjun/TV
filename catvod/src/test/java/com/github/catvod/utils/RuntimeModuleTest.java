package com.github.catvod.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * Covers the pure validation branches of {@link RuntimeModule}. Visibility is package private on
 * those members so the checks that decide whether a downloaded plugin APK is trusted can be
 * exercised without a signed fixture APK or a real device.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class RuntimeModuleTest {

    private static final String SHA = "9b4de1fb7beb7f679fee78bd506fdd6a56a097095b29e31647a9657d67d99155";

    @After
    public void clearOverrides() {
        for (RuntimeModule module : RuntimeModule.values()) module.setCompatibilityId(null);
    }

    @Test
    public void compatibilityIdsMatchPublishedCatalog() throws Exception {
        JSONObject catalog = publishedCatalog();
        JSONArray modules = catalog.getJSONArray("modules");
        for (int i = 0; i < modules.length(); i++) {
            JSONObject entry = modules.getJSONObject(i);
            RuntimeModule module = RuntimeModule.valueOf(entry.getString("id").toUpperCase());
            if (module == RuntimeModule.MPV) continue;
            assertEquals(entry.getString("id"), module.id);
            assertEquals(entry.getString("id"), entry.getString("packageName"), "com.fongmi.runtime." + module.id);
            assertEquals(entry.getString("id"), entry.getString("compatibilityId"), module.getCompatibilityId());
        }
        assertEquals(6, modules.length());
    }

    @Test
    public void compatibilityIdOverrideWinsAndResets() {
        RuntimeModule mpv = RuntimeModule.MPV;
        mpv.setCompatibilityId("mpv-420e49241a4524fc");
        assertEquals("mpv-420e49241a4524fc", mpv.getCompatibilityId());
        mpv.setCompatibilityId("");
        assertNotEquals("mpv-420e49241a4524fc", mpv.getCompatibilityId());
        mpv.setCompatibilityId(null);
        assertNotNull(mpv.getCompatibilityId());
        assertEquals("catvod-python-plugin-v4", RuntimeModule.PYTHON.getCompatibilityId());
    }

    @Test
    public void entryClassesAreOnlyDeclaredForJavaRuntimes() {
        assertEquals("com.fongmi.chaquo.RuntimeEntry", RuntimeModule.PYTHON.getEntryClass());
        assertEquals("com.fongmi.nodejs.RuntimeEntry", RuntimeModule.NODEJS.getEntryClass());
        assertEquals("com.fongmi.quickjs.RuntimeEntry", RuntimeModule.QUICKJS.getEntryClass());
        assertEquals("com.xunlei.downloadlib.RuntimeEntry", RuntimeModule.THUNDER.getEntryClass());
        assertEquals("", RuntimeModule.MPV.getEntryClass());
        assertEquals("", RuntimeModule.BDJ.getEntryClass());
    }

    @Test
    public void classRoutingKeepsHostPackagesOut() {
        assertEquals(RuntimeModule.PYTHON, RuntimeModule.moduleForClass("com.chaquo.python.PyObject"));
        assertEquals(RuntimeModule.PYTHON, RuntimeModule.moduleForClass("com.fongmi.chaquo.RuntimeEntry"));
        assertEquals(RuntimeModule.NODEJS, RuntimeModule.moduleForClass("com.fongmi.nodejs.RuntimeEntry"));
        assertEquals(RuntimeModule.QUICKJS, RuntimeModule.moduleForClass("com.fongmi.quickjs.RuntimeEntry"));
        assertEquals(RuntimeModule.THUNDER, RuntimeModule.moduleForClass("com.xunlei.downloadlib.XLDownload"));
        assertNull(RuntimeModule.moduleForClass("com.fongmi.quickjs.host.Bridge"));
        assertNull(RuntimeModule.moduleForClass("com.github.catvod.utils.RuntimeModule"));
    }

    @Test
    public void elfHeaderMustMatchThePackagedAbi() throws IOException {
        RuntimeModule.verifyElf(elf(2, 183), "arm64-v8a");
        RuntimeModule.verifyElf(elf(1, 40), "armeabi-v7a");
        assertThrows(IOException.class, () -> RuntimeModule.verifyElf(elf(2, 183), "armeabi-v7a"));
        assertThrows(IOException.class, () -> RuntimeModule.verifyElf(elf(1, 40), "arm64-v8a"));
        assertThrows(IOException.class, () -> RuntimeModule.verifyElf(elf(1, 183), "arm64-v8a"));
        assertThrows(IOException.class, () -> RuntimeModule.verifyElf(elf(2, 40), "armeabi-v7a"));
        byte[] bigEndian = elf(2, 183);
        bigEndian[5] = 2;
        assertThrows(IOException.class, () -> RuntimeModule.verifyElf(bigEndian, "arm64-v8a"));
        byte[] notElf = elf(2, 183);
        notElf[1] = 'X';
        assertThrows(IOException.class, () -> RuntimeModule.verifyElf(notElf, "arm64-v8a"));
    }

    @Test
    public void payloadPathsStayInsideTheModuleLayout() throws IOException {
        RuntimeModule python = RuntimeModule.PYTHON;
        python.verifyPayloadPath(payload("lib/arm64-v8a/libpython3.10.so"), "arm64-v8a");
        python.verifyPayloadPath(payload("assets/chaquopy/app.imy"), "arm64-v8a");
        assertThrows(IOException.class, () -> python.verifyPayloadPath(payload("lib/armeabi-v7a/libpython3.10.so"), "arm64-v8a"));
        assertThrows(IOException.class, () -> python.verifyPayloadPath(payload("lib/arm64-v8a/libnode.so"), "arm64-v8a"));
        assertThrows(IOException.class, () -> python.verifyPayloadPath(payload("assets/chaquopy/../app.imy"), "arm64-v8a"));
        assertThrows(IOException.class, () -> python.verifyPayloadPath(payload("assets/chaquopy/a\\b.imy"), "arm64-v8a"));
        assertThrows(IOException.class, () -> python.verifyPayloadPath(payload("assets/chaquopy//app.imy"), "arm64-v8a"));
        assertThrows(IOException.class, () -> python.verifyPayloadPath(payload("assets/chaquopy/dir/"), "arm64-v8a"));
        assertThrows(IOException.class, () -> python.verifyPayloadPath(payload("assets/js/lib/net.js"), "arm64-v8a"));
        assertThrows(IOException.class, () -> RuntimeModule.MPV.verifyPayloadPath(payload("assets/anything.js"), "arm64-v8a"));
        RuntimeModule.BDJ.verifyPayloadPath(payload("assets/bdj/runtime.zip"), "arm64-v8a");
        assertThrows(IOException.class, () -> RuntimeModule.BDJ.verifyPayloadPath(payload("assets/bdj/other.zip"), "arm64-v8a"));
    }

    @Test
    public void payloadMetadataRejectsDuplicatesAndBadDigests() throws IOException {
        List<RuntimeModule.Payload> files = List.of(payload("lib/arm64-v8a/libnode.so"), payload("lib/arm64-v8a/libnodebridge.so"), payload("assets/nodejs/bootstrap.js"));
        assertEquals(3, RuntimeModule.NODEJS.verifyPayloadMetadata(files, "arm64-v8a").size());
        List<RuntimeModule.Payload> duplicates = new ArrayList<>(files);
        duplicates.add(payload("lib/arm64-v8a/libnode.so"));
        assertThrows(IOException.class, () -> RuntimeModule.NODEJS.verifyPayloadMetadata(duplicates, "arm64-v8a"));
        List<RuntimeModule.Payload> digest = List.of(new RuntimeModule.Payload("lib/arm64-v8a/libnode.so", "not-a-digest", 1));
        assertThrows(IOException.class, () -> RuntimeModule.NODEJS.verifyPayloadMetadata(digest, "arm64-v8a"));
        List<RuntimeModule.Payload> empty = List.of(new RuntimeModule.Payload("lib/arm64-v8a/libnode.so", SHA, 0));
        assertThrows(IOException.class, () -> RuntimeModule.NODEJS.verifyPayloadMetadata(empty, "arm64-v8a"));
        List<RuntimeModule.Payload> huge = List.of(new RuntimeModule.Payload("lib/arm64-v8a/libnode.so", SHA, 128L * 1024 * 1024 + 1));
        assertThrows(IOException.class, () -> RuntimeModule.NODEJS.verifyPayloadMetadata(huge, "arm64-v8a"));
    }

    @Test
    public void requiredPayloadsCoverEveryRuntimeContract() throws IOException {
        HashSet<String> python = pythonPayloads("arm64-v8a");
        RuntimeModule.PYTHON.verifyRequiredPayloads(python, "arm64-v8a");
        python.remove("assets/chaquopy/cacert.pem");
        assertThrows(IOException.class, () -> RuntimeModule.PYTHON.verifyRequiredPayloads(python, "arm64-v8a"));

        HashSet<String> quickjs = new HashSet<>(List.of("lib/arm64-v8a/libquickjs-wrapper.so", "assets/js/lib/net.js", "assets/js/lib/spider.js"));
        RuntimeModule.QUICKJS.verifyRequiredPayloads(quickjs, "arm64-v8a");
        quickjs.remove("assets/js/lib/net.js");
        assertThrows(IOException.class, () -> RuntimeModule.QUICKJS.verifyRequiredPayloads(quickjs, "arm64-v8a"));

        HashSet<String> nodejs = new HashSet<>(List.of("lib/arm64-v8a/libnode.so", "lib/arm64-v8a/libnodebridge.so", "assets/nodejs/bootstrap.js"));
        RuntimeModule.NODEJS.verifyRequiredPayloads(nodejs, "arm64-v8a");
        nodejs.remove("assets/nodejs/bootstrap.js");
        assertThrows(IOException.class, () -> RuntimeModule.NODEJS.verifyRequiredPayloads(nodejs, "arm64-v8a"));

        RuntimeModule.BDJ.verifyRequiredPayloads(new HashSet<>(), "arm64-v8a");
    }

    @Test
    public void metadataMustAgreeWithTheHostExpectations() throws Exception {
        PackageInfo info = packageInfo("com.fongmi.runtime.python", "1.1.4", 114);
        JSONObject json = pythonMetadata();
        RuntimeModule.PYTHON.verifyMetadata(json, info, "arm64-v8a");
        assertThrows(IOException.class, () -> RuntimeModule.PYTHON.verifyMetadata(pythonMetadata().put("module", "nodejs"), info, "arm64-v8a"));
        assertThrows(IOException.class, () -> RuntimeModule.PYTHON.verifyMetadata(pythonMetadata().put("packageName", "com.fongmi.runtime.nodejs"), info, "arm64-v8a"));
        assertThrows(IOException.class, () -> RuntimeModule.PYTHON.verifyMetadata(pythonMetadata().put("versionCode", 115), info, "arm64-v8a"));
        assertThrows(IOException.class, () -> RuntimeModule.PYTHON.verifyMetadata(pythonMetadata().put("versionName", "1.1.5"), info, "arm64-v8a"));
        assertThrows(IOException.class, () -> RuntimeModule.PYTHON.verifyMetadata(pythonMetadata().put("compatibilityId", "catvod-python-plugin-v3"), info, "arm64-v8a"));
        assertThrows(IOException.class, () -> RuntimeModule.PYTHON.verifyMetadata(pythonMetadata(), info, "armeabi-v7a"));
        assertThrows(IOException.class, () -> RuntimeModule.PYTHON.verifyMetadata(pythonMetadata().put("library", "lib/arm64-v8a/libnode.so"), info, "arm64-v8a"));
        RuntimeModule.MPV.setCompatibilityId("mpv-420e49241a4524fc");
        PackageInfo mpv = packageInfo("com.fongmi.runtime.mpv", "1.1.3", 113);
        JSONObject mpvJson = new JSONObject().put("module", "mpv").put("packageName", "com.fongmi.runtime.mpv").put("versionCode", 113).put("versionName", "1.1.3").put("compatibilityId", "mpv-420e49241a4524fc").put("abi", "arm64-v8a").put("library", "lib/arm64-v8a/libplayer.so");
        RuntimeModule.MPV.verifyMetadata(mpvJson, mpv, "arm64-v8a");
    }

    @Test
    public void javaRuntimeMetadataNeedsAnEntryClassAndCode() throws Exception {
        PackageInfo info = packageInfo("com.fongmi.runtime.python", "1.1.4", 114);
        info.applicationInfo.flags = ApplicationInfo.FLAG_HAS_CODE;
        RuntimeModule.PYTHON.verifyJavaRuntime(pythonMetadata().put("entryClass", "com.fongmi.chaquo.RuntimeEntry").put("javaApi", 1), info);
        assertThrows(IOException.class, () -> RuntimeModule.PYTHON.verifyJavaRuntime(pythonMetadata().put("entryClass", "com.fongmi.nodejs.RuntimeEntry").put("javaApi", 1), info));
        assertThrows(IOException.class, () -> RuntimeModule.PYTHON.verifyJavaRuntime(pythonMetadata().put("entryClass", "com.fongmi.chaquo.RuntimeEntry").put("javaApi", 2), info));
        info.applicationInfo.flags = 0;
        assertThrows(IOException.class, () -> RuntimeModule.PYTHON.verifyJavaRuntime(pythonMetadata().put("entryClass", "com.fongmi.chaquo.RuntimeEntry").put("javaApi", 1), info));
        RuntimeModule.MPV.verifyJavaRuntime(new JSONObject(), info);
    }

    @Test
    public void mpvHostLibraryContractRequiresOneDigest() throws Exception {
        assertEquals(SHA, RuntimeModule.readHostCppSha256(new JSONObject().put("libc++_shared.so", SHA)));
        assertThrows(IOException.class, () -> RuntimeModule.readHostCppSha256(new JSONObject()));
        assertThrows(IOException.class, () -> RuntimeModule.readHostCppSha256(new JSONObject().put("libc++_shared.so", SHA).put("liblog.so", SHA)));
        assertThrows(IOException.class, () -> RuntimeModule.readHostCppSha256(new JSONObject().put("libc++_shared.so", SHA.toUpperCase())));
        assertThrows(IOException.class, () -> RuntimeModule.readHostCppSha256(new JSONObject().put("libc++_shared.so", "short")));
    }

    @Test
    public void bdjArchivePathsStayInsideJ2reImage() throws IOException {
        HashSet<String> paths = new HashSet<>();
        RuntimeModule.verifyBdjPath("j2re-image/lib/rt.jar", paths);
        RuntimeModule.verifyBdjPath("j2re-image/lib/aarch64/server/libjvm.so", paths);
        assertThrows(IOException.class, () -> RuntimeModule.verifyBdjPath("j2re-image/lib/rt.jar", paths));
        assertThrows(IOException.class, () -> RuntimeModule.verifyBdjPath("j2re-image/../lib/rt.jar", paths));
        assertThrows(IOException.class, () -> RuntimeModule.verifyBdjPath("j2re-image/lib\\rt.jar", paths));
        assertThrows(IOException.class, () -> RuntimeModule.verifyBdjPath("j2re-image//lib/rt.jar", paths));
        assertThrows(IOException.class, () -> RuntimeModule.verifyBdjPath("lib/rt.jar", paths));
    }

    @Test
    public void missingExceptionIsFoundThroughTheCauseChain() {
        IllegalStateException wrapped = new IllegalStateException(new IOException(new RuntimeModule.MissingException(RuntimeModule.MPV)));
        assertEquals(RuntimeModule.MPV, RuntimeModule.MissingException.find(wrapped).module);
        assertNull(RuntimeModule.MissingException.find(new IllegalStateException("plain")));
        RuntimeModule.MissingException missing = new RuntimeModule.MissingException(RuntimeModule.NODEJS);
        assertTrue(missing.getMessage().contains("Node.js"));
    }

    @Test
    public void packageNameFollowsTheModuleId() {
        for (RuntimeModule module : RuntimeModule.values()) assertEquals("com.fongmi.runtime." + module.id, module.getPackageName());
        assertFalse(RuntimeModule.PYTHON.getPackageName().equals(RuntimeModule.NODEJS.getPackageName()));
    }

    private static JSONObject publishedCatalog() throws Exception {
        for (File directory = new File(".").getAbsoluteFile(); directory != null; directory = directory.getParentFile()) {
            File file = new File(directory, "catalog/catalog.json");
            if (file.isFile()) return new JSONObject(Files.readString(file.toPath()));
        }
        throw new AssertionError("catalog/catalog.json is missing from the repository");
    }

    private static JSONObject pythonMetadata() throws Exception {
        return new JSONObject().put("module", "python").put("packageName", "com.fongmi.runtime.python").put("versionCode", 114).put("versionName", "1.1.4").put("compatibilityId", "catvod-python-plugin-v4").put("abi", "arm64-v8a").put("library", "lib/arm64-v8a/libchaquopy_java.so");
    }

    private static PackageInfo packageInfo(String name, String version, long code) {
        PackageInfo info = new PackageInfo();
        info.packageName = name;
        info.versionName = version;
        info.versionCode = (int) code;
        info.setLongVersionCode(code);
        info.applicationInfo = new ApplicationInfo();
        return info;
    }

    private static RuntimeModule.Payload payload(String path) {
        return new RuntimeModule.Payload(path, SHA, 1024);
    }

    private static byte[] elf(int elfClass, int machine) {
        byte[] header = new byte[20];
        header[0] = 0x7f;
        header[1] = 'E';
        header[2] = 'L';
        header[3] = 'F';
        header[4] = (byte) elfClass;
        header[5] = 1;
        header[18] = (byte) (machine & 0xff);
        header[19] = (byte) ((machine >>> 8) & 0xff);
        return header;
    }

    private static HashSet<String> pythonPayloads(String abi) {
        HashSet<String> paths = new HashSet<>();
        for (String name : new String[]{"libcrypto_python.so", "libssl_python.so", "libsqlite3_python.so", "libcrypto_chaquopy.so", "libssl_chaquopy.so", "libsqlite3_chaquopy.so", "libpython3.10.so", "libpython3.so", "libchaquopy_java.so"})
            paths.add("lib/" + abi + "/" + name);
        for (String name : new String[]{"app.imy", "build.json", "bootstrap.imy", "stdlib-common.imy", "stdlib-" + abi + ".imy", "requirements-common.imy", "requirements-" + abi + ".imy", "cacert.pem"})
            paths.add("assets/chaquopy/" + name);
        return paths;
    }
}
