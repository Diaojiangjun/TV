package com.fongmi.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.*

/**
 * Generates a Java constant holding the MPV runtime compatibility id derived from
 * the actual native libraries shipped by the host app.
 */
abstract class GenerateMpvCompatibilityTask extends DefaultTask {

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    abstract RegularFileProperty getMpvArchive()

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    abstract RegularFileProperty getFfmpegArchive()

    @OutputDirectory
    abstract DirectoryProperty getOutputDirectory()

    @TaskAction
    void generate() {
        def directory = outputDirectory.get().asFile
        def target = new File(directory, 'com/fongmi/android/tv/MpvCompatibility.java')
        target.parentFile.mkdirs()
        def id = MpvNativeLibraries.compatibilityId(mpvArchive.get().asFile, ffmpegArchive.get().asFile)
        target.text = '''package com.fongmi.android.tv;

public final class MpvCompatibility {

    public static final String ID = "''' + id + '''";

    private MpvCompatibility() {
    }
}
'''
    }
}
