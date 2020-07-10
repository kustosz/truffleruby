/*
 * Copyright (c) 2013, 2019 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 2.0, or
 * GNU General Public License version 2, or
 * GNU Lesser General Public License version 2.1.
 */
package org.truffleruby.language.loader;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

import org.jcodings.Encoding;
import org.jcodings.specific.UTF8Encoding;
import org.truffleruby.Layouts;
import org.truffleruby.RubyContext;
import org.truffleruby.RubyLanguage;
import org.truffleruby.collections.ConcurrentOperations;
import org.truffleruby.core.array.ArrayOperations;
import org.truffleruby.core.encoding.EncodingManager;
import org.truffleruby.core.string.StringOperations;
import org.truffleruby.core.support.IONodes.IOThreadBufferAllocateNode;
import org.truffleruby.extra.TruffleRubyNodes;
import org.truffleruby.extra.ffi.Pointer;
import org.truffleruby.language.RubyConstant;
import org.truffleruby.language.control.JavaException;
import org.truffleruby.language.control.RaiseException;
import org.truffleruby.platform.NativeConfiguration;
import org.truffleruby.platform.Platform;
import org.truffleruby.platform.TruffleNFIPlatform;
import org.truffleruby.platform.TruffleNFIPlatform.NativeFunction;
import org.truffleruby.shared.Metrics;
import org.truffleruby.shared.TruffleRuby;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleFile;
import com.oracle.truffle.api.interop.InteropException;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.profiles.ConditionProfile;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.source.SourceSection;

public class FeatureLoader {

    private final RubyContext context;

    private final ReentrantLockFreeingMap<String> fileLocks = new ReentrantLockFreeingMap<>();
    /** Maps basename without extension -> autoload path -> autoload constant, to detect when require-ing a file already
     * registered with autoload.
     *
     * Synchronization: Both levels of Map and the Lists are protected by registeredAutoloadsLock. */
    private final Map<String, Map<String, List<RubyConstant>>> registeredAutoloads = new HashMap<>();
    private final ReentrantLock registeredAutoloadsLock = new ReentrantLock();

    private final Object cextImplementationLock = new Object();
    private boolean cextImplementationLoaded = false;

    private String cwd = null;
    private NativeFunction getcwd;
    private static final int PATH_MAX = 1024; // jnr-posix hard codes this value

    private static final String[] EXTENSIONS = new String[]{ TruffleRuby.EXTENSION, RubyLanguage.CEXT_EXTENSION };

    public FeatureLoader(RubyContext context) {
        this.context = context;
    }

    public void initialize(NativeConfiguration nativeConfiguration, TruffleNFIPlatform nfi) {
        if (context.getOptions().NATIVE_PLATFORM) {
            this.getcwd = nfi.getFunction("getcwd", "(pointer," + nfi.size_t() + "):pointer");
        }
    }

    public void addAutoload(RubyConstant autoloadConstant) {
        final String autoloadPath = autoloadConstant.getAutoloadConstant().getAutoloadPath();
        final String basename = basenameWithoutExtension(autoloadPath);

        registeredAutoloadsLock.lock();
        try {
            final Map<String, List<RubyConstant>> constants = ConcurrentOperations
                    .getOrCompute(registeredAutoloads, basename, k -> new LinkedHashMap<>());
            final List<RubyConstant> list = ConcurrentOperations
                    .getOrCompute(constants, autoloadPath, k -> new ArrayList<>());
            list.add(autoloadConstant);
        } finally {
            registeredAutoloadsLock.unlock();
        }
    }

    public List<RubyConstant> getAutoloadConstants(String expandedPath) {
        final String basename = basenameWithoutExtension(expandedPath);
        final Map<String, List<RubyConstant>> constantsMap;

        registeredAutoloadsLock.lock();
        try {
            constantsMap = registeredAutoloads.get(basename);
            if (constantsMap == null || constantsMap.isEmpty()) {
                return null;
            }

            final List<RubyConstant> constants = new ArrayList<>();
            for (Map.Entry<String, List<RubyConstant>> entry : constantsMap.entrySet()) {
                // NOTE: this call might be expensive but it seems difficult to move it outside the lock.
                // Contention does not seem a big issue since only addAutoload/removeAutoload need to wait.
                // At least, findFeature() does not access registeredAutoloads or use registeredAutoloadsLock.
                final String expandedAutoloadPath = findFeature(entry.getKey());

                if (expandedPath.equals(expandedAutoloadPath)) {
                    for (RubyConstant constant : entry.getValue()) {
                        // Do not autoload recursively from the #require call in GetConstantNode
                        if (!constant.getAutoloadConstant().isAutoloading()) {
                            constants.add(constant);
                        }
                    }
                }
            }

            if (constants.isEmpty()) {
                return null;
            } else {
                return constants;
            }
        } finally {
            registeredAutoloadsLock.unlock();
        }
    }

    public void removeAutoload(RubyConstant constant) {
        final String autoloadPath = constant.getAutoloadConstant().getAutoloadPath();
        final String basename = basenameWithoutExtension(autoloadPath);

        registeredAutoloadsLock.lock();
        try {
            final Map<String, List<RubyConstant>> constantsMap = registeredAutoloads.get(basename);
            List<RubyConstant> constants = constantsMap.get(autoloadPath);
            if (constants != null) {
                constants.remove(constant);
            }
        } finally {
            registeredAutoloadsLock.unlock();
        }
    }

    private String basenameWithoutExtension(String path) {
        final String basename = new File(path).getName();
        int i = basename.lastIndexOf('.');
        if (i >= 0) {
            return basename.substring(0, i);
        } else {
            return basename;
        }
    }

    private boolean hasExtension(String path) {
        return path.endsWith(TruffleRuby.EXTENSION) || path.endsWith(RubyLanguage.CEXT_EXTENSION) ||
                path.endsWith(".so");
    }

    public void setWorkingDirectory(String cwd) {
        this.cwd = cwd;
    }

    public String getWorkingDirectory() {
        if (cwd != null) {
            return cwd;
        } else {
            return cwd = initializeWorkingDirectory();
        }
    }

    private String initializeWorkingDirectory() {
        final TruffleNFIPlatform nfi = context.getTruffleNFI();
        if (nfi == null) {
            // The current working cannot change if there are no native calls
            return context.getEnv().getCurrentWorkingDirectory().getPath();
        }
        final int bufferSize = PATH_MAX;
        final DynamicObject rubyThread = context.getThreadManager().getCurrentThread();
        final Pointer buffer = IOThreadBufferAllocateNode
                .getBuffer(rubyThread, bufferSize, ConditionProfile.getUncached());
        try {
            final long address = nfi.asPointer(getcwd.call(buffer.getAddress(), bufferSize));
            if (address == 0) {
                context.send(context.getCoreLibrary().errnoModule, "handle");
            }
            final byte[] bytes = buffer.readZeroTerminatedByteArray(context, 0);
            final Encoding localeEncoding = context.getEncodingManager().getLocaleEncoding();
            return new String(bytes, EncodingManager.charsetForEncoding(localeEncoding));
        } finally {
            Layouts.THREAD.setIoBuffer(
                    rubyThread,
                    Layouts.THREAD.getIoBuffer(rubyThread).free(ConditionProfile.getUncached()));
        }
    }

    /** Make a path absolute, by expanding relative to the context CWD. */
    private String makeAbsolute(String path) {
        final File file = new File(path);
        if (file.isAbsolute()) {
            return path;
        } else {
            String cwd = getWorkingDirectory();
            return new File(cwd, path).getPath();
        }
    }

    public String canonicalize(String path) {
        // First, make the path absolute, by expanding relative to the context CWD
        // Otherwise, getCanonicalPath() uses user.dir as CWD which is incorrect.
        final String absolutePath = makeAbsolute(path);
        try {
            return new File(absolutePath).getCanonicalPath();
        } catch (IOException e) {
            throw new JavaException(e);
        }
    }

    public String dirname(String absolutePath) {
        assert new File(absolutePath).isAbsolute();

        final String parent = new File(absolutePath).getParent();
        if (parent == null) {
            return absolutePath;
        } else {
            return parent;
        }
    }

    public ReentrantLockFreeingMap<String> getFileLocks() {
        return fileLocks;
    }


    @TruffleBoundary
    public String findFeature(String feature) {
        return context.getMetricsProfiler().callWithMetrics(
                "searching",
                feature,
                () -> findFeatureImpl(feature));
    }

    @TruffleBoundary
    public String findFeatureImpl(String feature) {
        if (context.getOptions().LOG_FEATURE_LOCATION) {
            final String originalFeature = feature;

            RubyLanguage.LOGGER.info(() -> {
                final SourceSection sourceSection = context.getCallStack().getTopMostUserSourceSection();
                return String.format(
                        "starting search from %s for feature %s...",
                        RubyContext.fileLine(sourceSection),
                        originalFeature);
            });

            RubyLanguage.LOGGER.info(String.format("current directory: %s", getWorkingDirectory()));
        }

        if (feature.startsWith("./")) {
            feature = getWorkingDirectory() + "/" + feature.substring(2);

            if (context.getOptions().LOG_FEATURE_LOCATION) {
                RubyLanguage.LOGGER.info(String.format("feature adjusted to %s", feature));
            }
        } else if (feature.startsWith("../")) {
            feature = dirname(getWorkingDirectory()) + "/" + feature.substring(3);

            if (context.getOptions().LOG_FEATURE_LOCATION) {
                RubyLanguage.LOGGER.info(String.format("feature adjusted to %s", feature));
            }
        }

        String found = null;

        if (feature.startsWith(RubyLanguage.RESOURCE_SCHEME) || new File(feature).isAbsolute()) {
            found = findFeatureWithAndWithoutExtension(feature);
        } else if (hasExtension(feature)) {
            for (Object pathObject : ArrayOperations.toIterable(context.getCoreLibrary().getLoadPath())) {
                // $LOAD_PATH entries are canonicalized since Ruby 2.4.4
                final String loadPath = canonicalize(pathObject.toString());

                if (context.getOptions().LOG_FEATURE_LOCATION) {
                    RubyLanguage.LOGGER.info(String.format("from load path %s...", loadPath));
                }

                String fileWithinPath = new File(loadPath, translateIfNativePath(feature)).getPath();
                final String result = findFeatureWithExactPath(fileWithinPath);

                if (result != null) {
                    found = result;
                    break;
                }
            }
        } else {
            extensionLoop: for (String extension : EXTENSIONS) {
                for (Object pathObject : ArrayOperations.toIterable(context.getCoreLibrary().getLoadPath())) {
                    // $LOAD_PATH entries are canonicalized since Ruby 2.4.4
                    final String loadPath = canonicalize(pathObject.toString());

                    if (context.getOptions().LOG_FEATURE_LOCATION) {
                        RubyLanguage.LOGGER.info(String.format("from load path %s...", loadPath));
                    }

                    final String fileWithinPath = new File(loadPath, feature).getPath();
                    final String result = findFeatureWithExactPath(fileWithinPath + extension);

                    if (result != null) {
                        found = result;
                        break extensionLoop;
                    }
                }
            }
        }

        if (context.getOptions().LOG_FEATURE_LOCATION) {
            if (found == null) {
                RubyLanguage.LOGGER.info("not found");
            } else {
                RubyLanguage.LOGGER.info(String.format("found in %s", found));
            }
        }

        return found;
    }

    private String translateIfNativePath(String feature) {
        if (!RubyLanguage.CEXT_EXTENSION.equals(".so") && feature.endsWith(".so")) {
            final String base = feature.substring(0, feature.length() - 3);
            return base + RubyLanguage.CEXT_EXTENSION;
        } else {
            return feature;
        }
    }

    private String findFeatureWithAndWithoutExtension(String path) {
        assert new File(path).isAbsolute();

        if (path.endsWith(".so")) {
            final String pathWithNativeExt = translateIfNativePath(path);
            final String asCExt = findFeatureWithExactPath(pathWithNativeExt);
            if (asCExt != null) {
                return asCExt;
            }
        }

        final String asRuby = findFeatureWithExactPath(path + TruffleRuby.EXTENSION);
        if (asRuby != null) {
            return asRuby;
        }

        final String asCExt = findFeatureWithExactPath(path + RubyLanguage.CEXT_EXTENSION);
        if (asCExt != null) {
            return asCExt;
        }

        final String withoutExtension = findFeatureWithExactPath(path);
        if (withoutExtension != null) {
            return withoutExtension;
        }

        return null;
    }

    private String findFeatureWithExactPath(String path) {
        if (context.getOptions().LOG_FEATURE_LOCATION) {
            RubyLanguage.LOGGER.info(String.format("trying %s...", path));
        }

        if (path.startsWith(RubyLanguage.RESOURCE_SCHEME)) {
            return path;
        }

        final File file = new File(path);
        if (!file.isFile()) {
            return null;
        }

        // Normalize path like File.expand_path() (e.g., remove "../"), but do not resolve symlinks
        return file.toPath().normalize().toString();
    }

    @TruffleBoundary
    public void ensureCExtImplementationLoaded(String feature, RequireNode requireNode) {
        synchronized (cextImplementationLock) {
            if (cextImplementationLoaded) {
                return;
            }

            if (!context.getOptions().CEXTS) {
                throw new RaiseException(
                        context,
                        context.getCoreExceptions().loadError(
                                "cannot load as C extensions are disabled with -Xcexts=false",
                                feature,
                                null));
            }

            if (!TruffleRubyNodes.SulongNode.isSulongAvailable(context)) {
                throw new RaiseException(
                        context,
                        context.getCoreExceptions().loadError(
                                "Sulong is required to support C extensions, and it doesn't appear to be available",
                                feature,
                                null));
            }

            Metrics.printTime("before-load-cext-support");
            try {
                final DynamicObject cextRb = StringOperations
                        .createString(context, StringOperations.encodeRope("truffle/cext", UTF8Encoding.INSTANCE));
                context.send(context.getCoreLibrary().mainObject, "gem_original_require", cextRb);

                final DynamicObject truffleModule = context.getCoreLibrary().truffleModule;
                final Object truffleCExt = Layouts.MODULE.getFields(truffleModule).getConstant("CExt").getValue();

                final String rubyLibPath = context.getRubyHome() + "/lib/cext/libtruffleruby" + Platform.LIB_SUFFIX;
                final Object library = loadCExtLibRuby(rubyLibPath, feature);

                final Object initFunction = requireNode
                        .findFunctionInLibrary(library, "rb_tr_init", rubyLibPath);

                final InteropLibrary interop = InteropLibrary.getFactory().getUncached();
                try {
                    // rb_tr_init(Truffle::CExt)
                    interop.execute(initFunction, truffleCExt);
                    // Truffle::CExt.register_libtruffleruby(libtruffleruby)
                    interop.invokeMember(truffleCExt, "register_libtruffleruby", library);
                } catch (InteropException e) {
                    throw new JavaException(e);
                }
            } finally {
                Metrics.printTime("after-load-cext-support");
            }

            cextImplementationLoaded = true;
        }
    }

    private Object loadCExtLibRuby(String rubyLibPath, String feature) {
        if (context.getOptions().CEXTS_LOG_LOAD) {
            RubyLanguage.LOGGER.info(() -> String.format("loading cext implementation %s", rubyLibPath));
        }

        if (!new File(rubyLibPath).exists()) {
            throw new RaiseException(
                    context,
                    context.getCoreExceptions().loadError(
                            "this TruffleRuby distribution does not have the C extension implementation file " +
                                    rubyLibPath,
                            feature,
                            null));
        }

        return loadCExtLibrary("libtruffleruby", rubyLibPath);
    }

    @TruffleBoundary
    public Object loadCExtLibrary(String feature, String path) {
        Metrics.printTime("before-load-cext-" + feature);
        try {
            final TruffleFile truffleFile = FileLoader.getSafeTruffleFile(context, path);
            if (!truffleFile.exists()) {
                throw new RaiseException(
                        context,
                        context.getCoreExceptions().loadError(path + " does not exist", path, null));
            }

            final Source source = Source.newBuilder("llvm", truffleFile).build();
            final Object result;
            try {
                result = context.getEnv().parseInternal(source).call();
            } catch (Exception e) {
                throw new JavaException(e);
            }
            return result;
        } catch (IOException e) {
            throw new JavaException(e);
        } finally {
            Metrics.printTime("after-load-cext-" + feature);
        }
    }

    // TODO (pitr-ch 16-Mar-2016): this protects the $LOADED_FEATURES only in this class,
    // it can still be accessed and modified (rare) by Ruby code which may cause issues
    private final Object loadedFeaturesLock = new Object();

    public Object getLoadedFeaturesLock() {
        return loadedFeaturesLock;
    }

}
