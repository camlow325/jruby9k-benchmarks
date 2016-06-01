package jruby9k;

import com.sun.management.UnixOperatingSystemMXBean;
import jnr.posix.POSIX;
import jnr.posix.POSIXFactory;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.jruby.RubyInstanceConfig;
import org.jruby.embed.LocalContextScope;
import org.jruby.embed.PathType;
import org.jruby.embed.ScriptingContainer;

import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FileDescLeakDriver {

    public static List<String> lsofJrubyJars() throws InterruptedException, IOException {
        POSIX posix = POSIXFactory.getPOSIX();
        int pid = posix.getpid();
        ProcessBuilder pb = new ProcessBuilder("lsof", "-p", String.valueOf(pid));
        Process proc = pb.start();
        proc.waitFor();
        String stdout = IOUtils.toString(proc.getInputStream(), "UTF-8");
        IOUtils.toString(proc.getErrorStream(), "UTF-8");

        String[] lines = StringUtils.split(stdout, '\n');
        List<String> rv = new ArrayList<>();

        for (String line : lines) {
            if (line.contains("jruby") && line.endsWith(".jar")) {
                rv.add(line);
            }
        }
        return rv;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> getJarIndexCache() throws
            ClassNotFoundException,
            InterruptedException,
            NoSuchFieldException,
            IllegalAccessException
    {
        Class jarResourceClass = Class.forName("org.jruby.util.JarResource");
        Field jarCacheField = jarResourceClass.getDeclaredField("jarCache");
        jarCacheField.setAccessible(true);
        Object jarCache = jarCacheField.get(jarResourceClass);
        Class jarCacheClass = jarCache.getClass();
        Field indexCacheField = jarCacheClass.getDeclaredField("indexCache");
        indexCacheField.setAccessible(true);

        Map<String, Object> fred =
                (Map<String, Object>) indexCacheField.get(jarCache);

        return fred;
    }

    public static void main(final String[] args) throws
            IOException,
            InterruptedException,
            NoSuchFieldException,
            NoSuchMethodException,
            ClassNotFoundException,
            IllegalAccessException,
            InvocationTargetException
    {
        System.out.println("Opening file_desc_leak_output.txt, setting as JRuby stdout.");
        Writer writer = new FileWriter("./file_desc_leak_output.txt", true);

        Map<String, Object> jarIndexCache = getJarIndexCache();

        UnixOperatingSystemMXBean os = (UnixOperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        long initialFdCount = os.getOpenFileDescriptorCount();
        int initialJRubyJarFdCount = lsofJrubyJars().size();

        Class<?> jarIndexClass = Class.forName("org.jruby.util.JarCache$JarIndex");
        Method jarIndexReleaseMethod = jarIndexClass.getMethod("release", null);
        jarIndexReleaseMethod.setAccessible(true);

        for (int i = 0; i < 10; i++) {
            ScriptingContainer sc = new ScriptingContainer(LocalContextScope.SINGLETHREAD);
            sc.setOutput(writer);
            sc.setCompileMode(RubyInstanceConfig.CompileMode.OFF);
            sc.setEnvironment(new HashMap<String, String>() {{
                put("COMPILE_MODE", "OFF");
            }});
            sc.runScriptlet(PathType.RELATIVE, "./file_desc_leak.rb");

            URL[] classLoaderUrls =
                    sc.getProvider().getRuntime().getJRubyClassLoader().getURLs();

            for (URL classLoaderUrl : classLoaderUrls) {
                String urlPath = classLoaderUrl.getPath();
                Object jarEntry = jarIndexCache.get(urlPath);
                if (jarEntry != null) {
                    jarIndexCache.remove(urlPath);
                    jarIndexReleaseMethod.invoke(jarEntry, null);
                }
            }

            sc.terminate();

            writer.write("Iteration #" + i + "; number of open fd: " + os.getOpenFileDescriptorCount() + "\n");
        }

        writer.write("Final list of JRuby jar fds from lsof output:\n\n");
        long finalFdCount = os.getOpenFileDescriptorCount();
        List<String> finalJRubyJarFds = lsofJrubyJars();
        for (String fd : finalJRubyJarFds) {
            writer.write(fd + "\n");
        }

        writer.write("Final FD count exceeds initial FD count by: " + (finalFdCount - initialFdCount) + "\n");
        writer.write("Final JRuby Jar FD count exceeds initial by: " + (finalJRubyJarFds.size() - initialJRubyJarFdCount) + "\n");

        writer.close();
    }

}
