package com.fongmi.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

import javax.inject.Inject
import java.util.regex.Pattern
import java.util.zip.ZipFile

/**
 * Verifies the native MPV bridge against the Java class shipped in the AAR.
 *
 * libplayer.so caches Java methods at runtime; a missing method or descriptor
 * crashes playback with NoSuchMethodError/ClassNotFoundException. Keep this
 * list in sync with mpv-android's jni_utils.cpp and stream.cpp.
 */
abstract class VerifyMpvJniTask extends DefaultTask {

    private static final Map<String, List<String>> REQUIRED_METHODS = [
            'is.xyz.mpv.MPVLib'       : [
                    'eventProperty'    : [
                            '(Ljava/lang/String;)V',
                            '(Ljava/lang/String;Z)V',
                            '(Ljava/lang/String;J)V',
                            '(Ljava/lang/String;D)V',
                            '(Ljava/lang/String;Ljava/lang/String;)V'
                    ],
                    'event'            : ['(I)V'],
                    'eventCommandReply': ['(JIJ)V'],
                    'eventEndFile'     : ['(IILjava/lang/String;)V'],
                    'logMessage'       : ['(Ljava/lang/String;ILjava/lang/String;)V'],
                    'openStream'       : ['(Ljava/lang/String;)Lis/xyz/mpv/MPVLib$Stream;']
            ],
            'is.xyz.mpv.MPVLib$Stream': [
                    'read'  : ['(Ljava/nio/ByteBuffer;)I'],
                    'seek'  : ['(J)J'],
                    'size'  : ['()J'],
                    'cancel': ['()V'],
                    'close' : ['()V']
            ]
    ].asImmutable()

    private static final List<String> ABIS = ['arm64-v8a', 'armeabi-v7a']

    @InputFile
    abstract RegularFileProperty getMpvArchive()

    @InputFile
    abstract RegularFileProperty getFfmpegArchive()

    @Inject
    abstract ExecOperations getExecOperations()

    @TaskAction
    void verify() {
        verifyMpvBridge()
        verifyFfmpegVersions()
    }

    void verifyMpvBridge() {
        File classesJar = new File(temporaryDir, 'mpv-classes.jar')
        new ZipFile(mpvArchive.get().asFile).withCloseable { archive ->
            extract(archive, 'classes.jar', classesJar)
            verifyProguardRules(archive)
        }
        REQUIRED_METHODS.each { String className, Map<String, List<String>> methods ->
            Map<String, Set<String>> found = readMethods(classesJar, className)
            methods.each { String name, List<String> descriptors ->
                descriptors.each { String descriptor ->
                    if (!found.getOrDefault(name, Collections.emptySet()).contains(descriptor)) {
                        throw new GradleException("${className}.${name}${descriptor} is required by libplayer.so but missing from ${mpvArchive.get().asFile.name}")
                    }
                }
            }
        }
    }

    void verifyProguardRules(ZipFile archive) {
        String rules = new String(readEntry(archive, 'proguard.txt'), 'UTF-8')
        if (!rules.contains('is.xyz.mpv.**') && !rules.contains('is.xyz.mpv.MPVLib$*')) {
            throw new GradleException("${mpvArchive.get().asFile.name} must keep is.xyz.mpv.** so R8 preserves MPVLib and MPVLib\$Stream")
        }
    }

    Map<String, Set<String>> readMethods(File classesJar, String className) {
        File javap = new File(new File(System.getProperty('java.home'), 'bin'), onWindows() ? 'javap.exe' : 'javap')
        if (!javap.isFile()) {
            throw new GradleException("javap not found at ${javap}; a JDK is required to verify the MPV bridge")
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream()
        def result = execOperations.exec { spec ->
            spec.commandLine(javap.absolutePath, '-p', '-s', '-classpath', classesJar.absolutePath, className)
            spec.standardOutput = output
            spec.errorOutput = output
            spec.ignoreExitValue = true
        }
        if (result.exitValue != 0) {
            throw new GradleException("Unable to inspect ${className} in ${mpvArchive.get().asFile.name}:\n${output.toString('UTF-8')}")
        }
        Map<String, Set<String>> methods = [:]
        String pending = null
        output.toString('UTF-8').readLines().each { String line ->
            String trimmed = line.trim()
            if (trimmed.startsWith('descriptor:')) {
                if (pending != null) {
                    def match = pending =~ /([A-Za-z_$][\w$]*)\s*\(/
                    if (match.find()) {
                        methods.computeIfAbsent(match.group(1), { new LinkedHashSet<String>() })
                                .add(trimmed.substring('descriptor:'.length()).trim())
                    }
                }
                pending = null
            } else if (line.contains('(')) {
                pending = line
            }
        }
        return methods
    }

    void verifyFfmpegVersions() {
        new ZipFile(ffmpegArchive.get().asFile).withCloseable { ffmpeg ->
            ABIS.each { String abi ->
                byte[] jni = readEntry(ffmpeg, "jni/${abi}/libffmpegJNI.so")
                byte[] library = readEntry(ffmpeg, "jni/${abi}/libffmpeg.so")
                Set<String> required = versionSymbols(jni)
                if (required.isEmpty()) {
                    throw new GradleException("No FFmpeg symbol versions found in libffmpegJNI.so for ${abi}")
                }
                Set<String> provided = versionSymbols(library)
                Set<String> missing = required - provided
                if (!missing.isEmpty()) {
                    throw new GradleException("libffmpeg.so for ${abi} is missing symbol versions ${missing}; rebuild the FFmpeg natives")
                }
            }
        }
    }

    Set<String> versionSymbols(byte[] bytes) {
        String text = new String(bytes, 'ISO-8859-1')
        Set<String> result = new LinkedHashSet<>()
        def matcher = Pattern.compile(/LIB(?:AV|SW)[A-Z]+_\d+/).matcher(text)
        while (matcher.find()) result.add(matcher.group())
        return result
    }

    byte[] readEntry(ZipFile archive, String name) {
        def entry = archive.getEntry(name)
        if (entry == null) throw new GradleException("Missing ${name} in ${archive.name}")
        ByteArrayOutputStream output = new ByteArrayOutputStream()
        archive.getInputStream(entry).withCloseable { input -> input.transferTo(output) }
        return output.toByteArray()
    }

    void extract(ZipFile archive, String name, File target) {
        def entry = archive.getEntry(name)
        if (entry == null) throw new GradleException("Missing ${name} in ${archive.name}")
        target.parentFile.mkdirs()
        archive.getInputStream(entry).withCloseable { input ->
            target.withOutputStream { output -> input.transferTo(output) }
        }
    }

    boolean onWindows() {
        return System.getProperty('os.name').toLowerCase(Locale.ROOT).contains('windows')
    }
}


