package io.gutapk.tools

import java.net.URL
import java.net.URLClassLoader
import java.util.Collections
import java.util.Enumeration

// Flink's loader for user code: a class is looked for in the URLs first,
// then in the parent, but for the patterns that always go to the parent.
// Changed: owned names the classes that stay this loader's even though a
// pattern covers them (a class of GutapK built against a library, and its
// inner classes), since GutapK's own jar is also the parent's.
// https://github.com/apache/flink/blob/release-2.2.1/flink-core/src/main/java/org/apache/flink/util/ChildFirstClassLoader.java
// not ported: loadClassWithoutExceptionHandling (its body is loadClass here, FlinkUserCodeClassLoader is what calls it)
// not ported: copy (the loader is made again for each use)
// not ported: hasMoreElements (the anonymous Enumeration of getResources, Collections.enumeration is the same)
// not ported: nextElement (the same Enumeration)
class ChildFirstClassLoader(
    urls: Array<URL>,
    parent: ClassLoader,
    private val alwaysParentFirstPatterns: Array<String>,
    private val owned: Array<String>,
) : URLClassLoader(urls, parent) {
    override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(getClassLoadingLock(name)) {
        // First, check if the class has already been loaded
        var c = findLoadedClass(name)

        if (c == null) {
            // check whether the class should go parent-first
            if (owned.none { name == it || name.startsWith("$it$") }) {
                for (alwaysParentFirstPattern in alwaysParentFirstPatterns) {
                    if (name.startsWith(alwaysParentFirstPattern)) {
                        return super.loadClass(name, resolve)
                    }
                }
            }

            c = try {
                // check the URLs
                findClass(name)
            } catch (e: ClassNotFoundException) {
                // let URLClassLoader do it, which will eventually call the parent
                super.loadClass(name, resolve)
            }
        } else if (resolve) {
            resolveClass(c)
        }

        c
    }

    override fun getResource(name: String): URL? {
        // first, try and find it via the URLClassloader
        val urlClassLoaderResource = findResource(name)

        if (urlClassLoaderResource != null) {
            return urlClassLoaderResource
        }

        // delegate to super
        return super.getResource(name)
    }

    override fun getResources(name: String): Enumeration<URL> {
        // first get resources from URLClassloader
        val urlClassLoaderResources = findResources(name)

        val result = ArrayList<URL>()

        while (urlClassLoaderResources.hasMoreElements()) {
            result.add(urlClassLoaderResources.nextElement())
        }

        // get parent urls
        val parentResources = parent.getResources(name)

        while (parentResources.hasMoreElements()) {
            result.add(parentResources.nextElement())
        }

        return Collections.enumeration(result)
    }

    companion object {
        init {
            ClassLoader.registerAsParallelCapable()
        }
    }
}
