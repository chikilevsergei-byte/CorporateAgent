package corpagent;
/** Verifies that the built ZIP is loadable as a Java JAR on Windows. */
public class PackageProbe {
 public static void main(String[] args) throws Exception {
  java.io.File file = new java.io.File(args.length == 0 ? "dist/CorporateAgent.jar" : args[0]);
  try (java.util.jar.JarFile jar = new java.util.jar.JarFile(file)) {
   if (jar.getManifest() == null || jar.getJarEntry("corpagent/App.class") == null || jar.getJarEntry("web/index.html") == null)
    throw new AssertionError("Missing JAR manifest, entry point or web assets");
   if (jar.getJarEntry("corpagent/api/DeepSeekLlmTester.class") == null) throw new AssertionError("Production LLM tester excluded");
   if (jar.getJarEntry("corpagent/api/WebFlowTest.class") != null) throw new AssertionError("Test class packaged");
  }
  try (java.net.URLClassLoader loader = new java.net.URLClassLoader(new java.net.URL[]{file.toURI().toURL()}, null)) {
   loader.loadClass("corpagent.App");
  }
  java.nio.file.Path temp = java.nio.file.Files.createTempDirectory("ca-first-run");
  corpagent.util.Config config = new corpagent.util.Config(temp.resolve("config.json").toString());
  if (!config.getApiKey().isEmpty() || !java.nio.file.Files.isRegularFile(temp.resolve("config.json"))) throw new AssertionError("First-run config not initialized");
  System.out.println("PackageProbe OK: production JAR loads");
 }
}
