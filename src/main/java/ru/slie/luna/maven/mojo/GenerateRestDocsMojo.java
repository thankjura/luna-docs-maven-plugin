package ru.slie.luna.maven.mojo;

import io.swagger.v3.core.util.Yaml;
import io.swagger.v3.oas.models.OpenAPI;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import ru.slie.luna.maven.docs.I18nResolver;
import ru.slie.luna.maven.docs.LunaDocsGenerator;

import java.io.File;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Mojo(name = "generate-openapi", defaultPhase = LifecyclePhase.PREPARE_PACKAGE, requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME, threadSafe = true)
public class GenerateRestDocsMojo extends AbstractMojo {
    private final I18nResolver i18n = new I18nResolver("messages");


    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(property = "scan_packages", required = true)
    private List<String> scanPackages;

    @Parameter(property = "bundle_name", defaultValue = "api_messages")
    private String bundleName;

    @Parameter(defaultValue = "${project.build.outputDirectory}", readonly = true)
    private File outputDirectory;

    @Override
    public void execute() throws MojoExecutionException {
        ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            ClassLoader loader = getProjectClassLoader();
            Thread.currentThread().setContextClassLoader(loader);

            List<Locale> locales = LunaDocsGenerator.getLocales(project, bundleName);
            LunaDocsGenerator generator = new LunaDocsGenerator(project, loader, scanPackages);

            OpenAPI rawOas = generator.getOpenApi();

            for (Locale locale : locales) {
                I18nResolver apiResolver = new I18nResolver(bundleName, locale, loader);
                if (apiResolver.contains("api.rest.title")) {
                    rawOas.getInfo().setTitle(apiResolver.t("api.rest.title"));
                } else {
                    rawOas.getInfo().setTitle(project.getName());
                }

                OpenAPI localized = generator.localize(rawOas, apiResolver);

                String fileName = "rest";
                if (!locale.equals(Locale.ROOT)) {
                    fileName += "_" + locale.getLanguage();
                }
                fileName += ".yaml";

                File targetFile = new File(outputDirectory, fileName);
                getLog().info(i18n.t("luna.maven.doc.write_to", targetFile.getAbsolutePath()));
                Yaml.mapper().writeValue(targetFile, localized);
            }
        } catch (Exception e) {
            throw new MojoExecutionException(i18n.t("luna.maven.doc.error"), e);
        } finally {
            Thread.currentThread().setContextClassLoader(originalClassLoader);
        }
    }

    private ClassLoader getProjectClassLoader() throws Exception {
        Set<URI> uris = new LinkedHashSet<>();
        uris.add(new File(project.getBuild().getOutputDirectory()).toURI());

        for (String element: project.getRuntimeClasspathElements()) {
            uris.add(new File(element).toURI());
        }

        for (String element : project.getCompileClasspathElements()) {
            uris.add(new File(element).toURI());
        }

        URL[] urls = new URL[uris.size()];
        int i = 0;
        for (URI uri : uris) {
            urls[i++] = uri.toURL();
        }

        return new URLClassLoader(urls, getClass().getClassLoader());
    }
}
