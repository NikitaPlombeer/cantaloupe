package edu.illinois.library.cantaloupe.script;

import edu.illinois.library.cantaloupe.config.Configuration;
import edu.illinois.library.cantaloupe.config.Key;
import edu.illinois.library.cantaloupe.util.Stopwatch;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.script.Invocable;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.locks.StampedLock;

/**
 * @see <a href="https://github.com/jruby/jruby/wiki/Embedding-with-JSR-223">
 *     Embedding JRuby with JSR223 - Code Examples</a>
 * @see <a href="https://github.com/jruby/jruby/wiki/RedBridge">RedBridge</a>
 */
class RubyScriptEngine extends AbstractScriptEngine
        implements ScriptEngine {

    private static final Logger LOGGER = LoggerFactory.
            getLogger(RubyScriptEngine.class);

    /** Top-level Ruby module containing methods to invoke. */
    static final String TOP_MODULE = "Cantaloupe";

    private final InvocationCache invocationCache =
            new HeapInvocationCache();
    private javax.script.ScriptEngine scriptEngine;
    private final StampedLock stampedLock = new StampedLock();

    static {
        // Available values are singleton, singlethread, threadsafe and
        // concurrent (JSR-223 default). See
        // https://github.com/jruby/jruby/wiki/RedBridge#Context_Instance_Type
        System.setProperty("org.jruby.embed.localcontext.scope", "concurrent");

        // Available values are transient, persistent, global (JSR-223 default)
        // and bsf. See
        // https://github.com/jruby/jruby/wiki/RedBridge#Local_Variable_Behavior_Options
        System.setProperty("org.jruby.embed.localvariable.behavior", "transient");
    }

    /**
     * @param methodName Name of the method being invoked.
     * @param args       Method arguments.
     * @return           Cache key corresponding to the given arguments.
     */
    private Object getCacheKey(String methodName, Object... args) {
        // The cache key is comprised of the method name at position 0 followed
        // by the arguments at succeeding positions.
        List<Object> key = Arrays.asList(args);
        key = new ArrayList<>(key);
        key.add(0, methodName);
        return key;
    }

    /**
     * @return Method invocation cache.
     */
    @Override
    public InvocationCache getInvocationCache() {
        return invocationCache;
    }

    /**
     * @param methodName Full method name including module names.
     * @return           Module name.
     */
    String getModuleName(String methodName) {
        final String[] parts = StringUtils.split(methodName, "::");
        if (parts.length == 1) {
            return TOP_MODULE;
        }
        final List<String> partsArr = Arrays.asList(parts);
        return TOP_MODULE + "::" +
                String.join("::", partsArr.subList(0, partsArr.size() - 1));
    }

    /**
     * @param methodName Full method name including module names.
     * @return           Method name excluding module names.
     */
    String getUnqualifiedMethodName(String methodName) {
        String[] parts = StringUtils.split(methodName, "::");
        return parts[parts.length - 1];
    }

    /**
     * N.B. Clients should not modify the returned object nor any of its owned
     * objects, as this could disrupt the invocation cache.
     *
     * @param methodName Method to invoke, including all prefixes except the
     *                   top-level one in {@link #TOP_MODULE}.
     * @param args       Arguments to pass to the method.
     * @return           Return value of the method.
     */
    @Override
    public Object invoke(String methodName, Object... args)
            throws ScriptException {
        final long stamp = stampedLock.readLock();
        try {
            final Stopwatch watch = new Stopwatch();

            Object returnValue;
            final Configuration config = Configuration.getInstance();
            if (config.getBoolean(Key.DELEGATE_METHOD_INVOCATION_CACHE_ENABLED, false)) {
                returnValue = retrieveFromCacheOrInvoke(methodName, args);
            } else {
                returnValue = doInvoke(methodName, args);
            }
            LOGGER.debug("invoke({}::{}): exec time: {} msec",
                    TOP_MODULE, methodName, watch.timeElapsed());
            return returnValue;
        } finally {
            stampedLock.unlock(stamp);
        }
    }

    @Override
    public void load(String code) throws ScriptException {
        final long stamp = stampedLock.writeLock();
        try {
            LOGGER.info("load(): loading script code");
            scriptEngine = new ScriptEngineManager().getEngineByName("jruby");
            scriptEngine.eval(code);
        } finally {
            stampedLock.unlock(stamp);
        }
    }

    private Object retrieveFromCacheOrInvoke(String methodName, Object... args)
            throws ScriptException {
        final Object cacheKey = getCacheKey(methodName, args);
        Object returnValue = invocationCache.get(cacheKey);
        if (returnValue != null) {
            LOGGER.debug("invoke({}::{}): cache hit (skipping invocation)",
                    TOP_MODULE, methodName);
        } else {
            LOGGER.debug("invoke({}::{}): cache miss", TOP_MODULE, methodName);
            returnValue = doInvoke(methodName, args);
            if (returnValue != null) {
                invocationCache.put(cacheKey, returnValue);
            }
        }
        return returnValue;
    }

    private Object doInvoke(String methodName, Object... args)
            throws ScriptException {
        try {
            return ((Invocable) scriptEngine).invokeMethod(
                    scriptEngine.eval(getModuleName(methodName)),
                    getUnqualifiedMethodName(methodName), args);
        } catch (NoSuchMethodException e) {
            throw new ScriptException(e);
        }
    }

}
