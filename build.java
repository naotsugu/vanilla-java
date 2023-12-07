import javax.tools.*;
import java.io.*;
import java.net.URI;
import java.nio.channels.Channels;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.function.*;

String mainClass = "example.Main";

Path srcDir = Path.of("src/main"), srcTestDir = Path.of("src/test"),
     libDir = Path.of("lib/main"), libTestDir = Path.of("lib/test"),
     outDir = Path.of("out/main"), outTestDir = Path.of("out/test"),
     jar    = libDir.resolve(Path.of("app.jar"));

List<String> libs = List.of(
    "org/apache/commons/commons-lang3/3.14.0/commons-lang3-3.14.0.jar");
List<String> testLibs = List.of(
    "org/junit/platform/junit-platform-console-standalone/1.10.1/junit-platform-console-standalone-1.10.1.jar");


void main(String... args) throws Exception {
    var opt = Set.of(args);
    if (opt.contains("init")) init();
    if (opt.contains("clean")) clean();
    if (opt.contains("build") || opt.isEmpty()) build();
    if (opt.contains("test")) test();
    if (opt.contains("run")) run();
}

void init() throws Exception {
    createDirectories(srcDir, srcTestDir, libDir, libTestDir, outDir, outTestDir);
    var main = srcDir.resolve(Path.of(mainClass.replace('.', '/') + ".java"));
    createDirectories(main.getParent());
    Files.writeString(main, mainTemplete(mainClass));
    var test = srcTestDir.resolve(Path.of(mainClass.replace('.', '/') + "Test.java"));
    createDirectories(test.getParent());
    Files.writeString(test, testTemplete(mainClass));
}

void clean() throws Exception {
    delete(outDir, jar);
}

void build() throws Exception {
    compile(srcDir, listSources(srcDir), fetch(libDir, libs), outDir);
    jar();
}

void test() throws Exception {
    var cp = new ArrayList<Path>(fetch(libTestDir, testLibs));
    cp.add(jar);
    compile(srcTestDir, listSources(srcTestDir), cp, outTestDir);
    execute("java", "-cp", libTestDir.toString() + "/*", "org.junit.platform.console.ConsoleLauncher",
        "--classpath", outTestDir.toString(),"--classpath", libDir.toString(), "--classpath", libTestDir.toString(),
        "--scan-classpath");
}

void jar() throws Exception {
    try (var os = new JarOutputStream(Files.newOutputStream(jar), createManifest(mainClass))) {
        for (Path path : list(outDir)) putJarEntry(path, os);
    }
}

void run() throws Exception {
    if (!Files.exists(jar)) build();
    execute("java", "-cp", libDir.toString() + "/*", mainClass);
}

//<editor-fold defaultstate="collapsed" desc="helper">

void compile(Path sourceDir, List<Path> units, List<Path> classPaths, Path outputDir) throws Exception {

    var compiler = ToolProvider.getSystemJavaCompiler();
    try (var fm = compiler.getStandardFileManager(null, null, null)) {

        Files.createDirectories(outputDir);
        fm.setLocationFromPaths(StandardLocation.SOURCE_PATH, List.of(sourceDir));
        fm.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(outputDir));
        if (!classPaths.isEmpty()) {
            fm.setLocationFromPaths(StandardLocation.CLASS_PATH, classPaths);
        }

        var options = List.of("--enable-preview", "--source", "21");
        compiler.getTask(null, fm, null, options, null, fm.getJavaFileObjectsFromPaths(units)).call();
    }
}

void execute(String... command) throws Exception {
    var pb = new ProcessBuilder(command);
    pb.redirectErrorStream(true);
    try (var stream = pb.start().getInputStream()) {
        stream.transferTo(System.out);
    }
}

Manifest createManifest(String mainFqcn) {
    Manifest mf = new Manifest();
    mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
    mf.getMainAttributes().put(Attributes.Name.MAIN_CLASS, mainFqcn);
    return mf;    
}

List<Path> fetch(Path dir, List<String> names) throws Exception {
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

void putJarEntry(Path source, JarOutputStream jar) throws IOException {
    var name = outDir.relativize(source).toString().replace('\\', '/');
    name += (Files.isDirectory(source) && !name.endsWith("/")) ? "/" : "";
    var entry = new JarEntry(name);
    entry.setTime(source.toFile().lastModified());
    jar.putNextEntry(entry);
    if (!Files.isDirectory(source)) Files.copy(source, jar);
    jar.closeEntry();
}

void delete(Path... paths) throws Exception {
    for (Path path : paths) {
        if (Files.isDirectory(path)) {
            Files.walk(outDir).sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        } else {
            Files.deleteIfExists(jar);
        }
    }
}

List<Path> listSources(Path rootDir) throws Exception {
    return list(rootDir, p -> p.toString().endsWith(".java"));
}

List<Path> list(Path rootDir) throws Exception {
    return list(rootDir, p -> true);
}

List<Path> list(Path rootDir, Predicate<Path> predicate) throws Exception {
    return Files.walk(rootDir).filter(p -> !p.equals(rootDir)).filter(predicate).toList();
}

void createDirectories(Path... dirs) throws Exception {
    for (Path dir : dirs) Files.createDirectories(dir);
}

String mainTemplete(String fqcn) {
    return """
    %s
    public class %s {
      public static void main(String... args) {
          System.out.println("Hello, World!");
      }
    }
    """.formatted(
        fqcn.contains(".") ? "package " + fqcn.replaceAll("\\.\\w+?$", "") + ";" : "",
        fqcn.replaceAll(".*\\.", "")
    );
}

String testTemplete(String fqcn) {
    return """
    %s
    import org.junit.jupiter.api.Test;
    import static org.junit.jupiter.api.Assertions.*;

    class %sTest {
        @Test void bind() {
            assertEquals(1, 1);
        }
    }
    """.formatted(
        fqcn.contains(".") ? "package " + fqcn.replaceAll("\\.\\w+?$", "") + ";" : "",
        fqcn.replaceAll(".*\\.", "")
    );
}

//</editor-fold>
