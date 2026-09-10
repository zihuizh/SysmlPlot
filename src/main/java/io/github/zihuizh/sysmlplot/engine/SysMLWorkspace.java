package io.github.zihuizh.sysmlplot.engine;

import com.google.inject.Injector;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

import org.eclipse.emf.common.util.TreeIterator;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.xtext.diagnostics.Severity;
import org.eclipse.xtext.util.CancelIndicator;
import org.eclipse.xtext.validation.CheckMode;
import org.eclipse.xtext.validation.IResourceValidator;
import org.eclipse.xtext.validation.Issue;
import org.omg.kerml.xtext.KerMLStandaloneSetup;
import org.omg.kerml.xtext.xmi.KerMLxStandaloneSetup;
import org.omg.sysml.interactive.SysMLInteractive;
import org.omg.sysml.lang.sysml.SysMLPackage;
import org.omg.sysml.lang.sysml.ViewUsage;
import org.omg.sysml.xtext.SysMLStandaloneSetup;
import org.omg.sysml.xtext.xmi.SysMLxStandaloneSetup;

/**
 * 一个已加载的 SysML v2 工作区：官方解析器 + 标准库 + 语义校验器。
 *
 * <p>加载顺序有讲究，见 {@link #load}。不要改成逐个文件 {@code next()} + {@code parse()}——
 * 那条路径不进 Xtext 索引，跨文件引用会全部解析失败。
 */
public final class SysMLWorkspace {

    private final SysMLInteractive sysml;
    private final IResourceValidator validator;
    private final Path workspaceRoot;
    private final List<Resource> workspaceResources;
    private final String modelDigest;

    private SysMLWorkspace(SysMLInteractive sysml,
                           IResourceValidator validator,
                           Path workspaceRoot,
                           List<Resource> workspaceResources,
                           String modelDigest) {
        this.sysml = sysml;
        this.validator = validator;
        this.workspaceRoot = workspaceRoot;
        this.workspaceResources = workspaceResources;
        this.modelDigest = modelDigest;
    }

    public static SysMLWorkspace load(Path libraryDir, Path workspaceDir) throws IOException {
        Path workspace = workspaceDir.toAbsolutePath().normalize();
        if (!Files.isDirectory(workspace)) {
            throw new IllegalArgumentException("workspace is not a directory: " + workspace);
        }

        SysMLInteractive sysml = SysMLInteractive.createInstance();
        sysml.setVerbose(false);
        if (libraryDir != null) {
            Path library = libraryDir.toAbsolutePath().normalize();
            if (!Files.isDirectory(library)) {
                throw new IllegalArgumentException("library is not a directory: " + library);
            }
            sysml.loadLibrary(library.toString());
        }
        sysml.readAll(workspace.toString(), true);
        sysml.resolveAllInputResources();

        String digest = computeModelDigest(workspace);
        return new SysMLWorkspace(sysml, createValidator(), workspace,
                List.copyOf(sysml.getInputResources()), digest);
    }

    public SysMLInteractive interactive() {
        return sysml;
    }

    public Path workspaceRoot() {
        return workspaceRoot;
    }

    public String modelDigest() {
        return modelDigest;
    }

    public List<Resource> workspaceResources() {
        return workspaceResources;
    }

    public boolean isWorkspaceResource(Resource resource) {
        return resource != null && workspaceResources.contains(resource);
    }

    public List<ViewUsage> views() {
        List<ViewUsage> views = new ArrayList<>();
        for (Resource resource : workspaceResources) {
            TreeIterator<EObject> iterator = resource.getAllContents();
            while (iterator.hasNext()) {
                if (iterator.next() instanceof ViewUsage viewUsage) {
                    views.add(viewUsage);
                }
            }
        }
        return views;
    }

    public List<Issue> validate(Resource resource) {
        return validator.validate(resource, CheckMode.ALL, CancelIndicator.NullImpl);
    }

    /** 工作区是否存在无法解决的语义错误。 */
    public boolean hasErrors() {
        for (Resource resource : workspaceResources) {
            for (Issue issue : validate(resource)) {
                if (issue.getSeverity() == Severity.ERROR) {
                    return true;
                }
            }
        }
        return false;
    }

    public int errorCount() {
        int count = 0;
        for (Resource resource : workspaceResources) {
            for (Issue issue : validate(resource)) {
                if (issue.getSeverity() == Severity.ERROR) {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * 取得 Xtext 的语义校验器：{@code SysMLInteractive.validate()} 只校验"当前资源"，
     * 而正规加载路径不设当前资源，所以必须自己建 injector。
     */
    private static IResourceValidator createValidator() {
        EPackage.Registry.INSTANCE.put(SysMLPackage.eNS_URI, SysMLPackage.eINSTANCE);
        KerMLStandaloneSetup.doSetup();
        KerMLxStandaloneSetup.doSetup();
        SysMLxStandaloneSetup.doSetup();
        Injector injector = new SysMLStandaloneSetup().createInjectorAndDoEMFRegistration();
        return injector.getInstance(IResourceValidator.class);
    }

    private static String computeModelDigest(Path root) throws IOException {
        List<Path> files;
        try (Stream<Path> stream = Files.walk(root)) {
            files = stream
                    .filter(Files::isRegularFile)
                    .filter(SysMLWorkspace::isSysMLSource)
                    .sorted(Comparator.comparing(path -> root.relativize(path).toString()))
                    .toList();
        }
        List<String> lines = new ArrayList<>(files.size());
        for (Path file : files) {
            String relative = root.relativize(file).toString().replace('\\', '/');
            lines.add(relative + "\u0000" + sha256Hex(Files.readAllBytes(file)));
        }
        return "sha256:" + sha256Hex(String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
    }

    private static boolean isSysMLSource(Path path) {
        String name = path.getFileName().toString();
        return name.endsWith(".sysml") || name.endsWith(".kerml");
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}

