//#!/usr/bin/java --enable-preview --source 21
// java --enable-preview --source 21 build.java

import javax.tools.*;
import java.io.*;
import java.net.URI;
import java.nio.channels.Channels;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;

Path srcDir = Path.of("src/"), outDir = Path.of("out/"), libDir = Path.of("lib/");
String main = "Main";
Path jar = libDir.resolve(Path.of("app.jar"));
String[] libs = new String[] {
    "org/apache/commons/commons-lang3/3.14.0/commons-lang3-3.14.0.jar"
};


void main(String... args) throws Exception {
    if (!Files.exists(srcDir)) init();
    var opt = Set.of(args);
    if (opt.contains("clean")) clean();
    if (opt.contains("build") || opt.isEmpty()) build();
    if (opt.contains("run")) run();
}


void build() throws Exception {

    var compiler = ToolProvider.getSystemJavaCompiler();
    try (StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, null)) {

        Files.createDirectories(outDir);
        fm.setLocationFromPaths(StandardLocation.SOURCE_PATH, List.of(srcDir));
        fm.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(outDir));
        if (libs != null && libs.length > 0) {
            fm.setLocationFromPaths(StandardLocation.CLASS_PATH, fetch(libDir, libs));
        }

        var units = fm.getJavaFileObjects(srcDir.resolve(main + ".java"));
        var options = List.of("--enable-preview", "--source", "21");

        compiler.getTask(null, fm, null, options, null, units).call();
    }
}


void test() throws Exception {
    fetch(libDir.resolve("test"), "org/junit/platform/junit-platform-console-standalone/1.10.1/junit-platform-console-standalone-1.10.1.jar");

}


void jar() throws Exception {

    Files.createDirectories(libDir);
    Manifest manifest = new Manifest();
    manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
    manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, main);
    
    try (var jarStream = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
        try (var stream = Files.list(outDir).filter(p -> !p.equals(outDir))) {
            for (Path path : stream.toList()) addJarEntry(path, jarStream);
        }
    }
}


void run() throws Exception {
    execCommand("java", "-cp", libDir.toString() + "/*", main);
}


void init() throws Exception {
    Files.createDirectories(srcDir);
    Files.writeString(srcDir.resolve("Main.java"), """
      public class Main {
          public static void main(String... args) {
              System.out.println("Hello, World!");
          }
      }
      """);
}


void clean() throws Exception {
    Files.walk(outDir)
            .sorted(Comparator.reverseOrder())
            .map(Path::toFile)
            .forEach(File::delete);
    Files.deleteIfExists(jar);
}


//<editor-fold defaultstate="collapsed" desc="utilities">

void addJarEntry(Path source, JarOutputStream jar) throws IOException {

    var name = outDir.relativize(source).toString().replace('\\', '/');
    name += (Files.isDirectory(source) && !name.endsWith("/")) ? "/" : "";
    var entry = new JarEntry(name);
    entry.setTime(source.toFile().lastModified());
    jar.putNextEntry(entry);
    
    if (Files.isDirectory(source)) {
        jar.closeEntry();
        try (var stream = Files.list(source)) {
            for (Path path : stream.toList()) addJarEntry(path, jar);
        }
    } else {
        Files.copy(source, jar);
        jar.closeEntry();
    }
}


List<Path> fetch(Path dir, String... names) throws Exception {

    Files.createDirectories(dir);
    var repo = "https://repo1.maven.org/maven2/";
    var ret = new ArrayList<Path>();
    
    for (var name : names) {
        var path = dir.resolve(name.substring(name.lastIndexOf("/") + 1));
        ret.add(path);
        if (Files.exists(path)) {
            continue;
        }

        System.out.println(" << " + path);
        try (var ch = Channels.newChannel(new URI(repo + name).toURL().openStream());
             var fc = new FileOutputStream(path.toFile()).getChannel()) {
            fc.transferFrom(ch, 0, Long.MAX_VALUE);
        }
    }

    return ret;
}


void compile() throws Exception {

}


void execCommand(String... command) throws Exception {
    ProcessBuilder pb = new ProcessBuilder(command);
    pb.redirectErrorStream(true);
    Process process = pb.start();
    try (InputStream stream = process.getInputStream()) {
        stream.transferTo(System.out);
    }
}

//</editor-fold>
