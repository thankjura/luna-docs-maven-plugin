package ru.slie.luna.maven.docs;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.core.converter.ResolvedSchema;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.models.*;
import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.tags.Tag;
import org.apache.maven.project.MavenProject;
import org.reflections.Reflections;
import org.reflections.scanners.Scanners;
import org.reflections.util.ClasspathHelper;
import org.reflections.util.ConfigurationBuilder;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.*;
import java.util.stream.Collectors;

public class LunaDocsGenerator {
    private final List<String> scanPackages;
    private final ClassLoader classLoader;
    private final MavenProject project;

    public LunaDocsGenerator(MavenProject project, ClassLoader classLoader, List<String> scanPackages) {
        this.project = project;
        this.scanPackages = scanPackages;
        this.classLoader = classLoader;
    }

    public static List<Locale> getLocales(MavenProject project, String bundleName) {
        List<Locale> locales = new ArrayList<>();
        File resourcesDir = new File(project.getBasedir(), "src/main/resources");

        File[] files = resourcesDir.listFiles((dir, name) -> name.startsWith(bundleName) && name.endsWith(".properties"));

        if (files != null) {
            for (File file : files) {
                String name = file.getName();
                String localePart = name.replace(bundleName, "")
                                            .replace(".properties", "")
                                            .replace("_", "");

                if (localePart.isEmpty()) {
                    locales.add(Locale.ROOT);
                } else {
                    locales.add(Locale.forLanguageTag(localePart));
                }
            }
        }

        return locales.isEmpty() ? Collections.singletonList(Locale.ENGLISH) : locales;
    }

    public OpenAPI getOpenApi() {
        OpenAPI oas = new OpenAPI();
        Paths paths = new Paths();
        Components components = new Components();
        oas.setComponents(components);

        Info info = new Info().version(project.getVersion());
        oas.setInfo(info);

        ConfigurationBuilder config = new ConfigurationBuilder()
                                              .addClassLoaders(classLoader)
                                              .setScanners(Scanners.TypesAnnotated, Scanners.SubTypes, Scanners.MethodsAnnotated)
                                              .forPackages(scanPackages.toArray(new String[0]));

        for (String pkg : scanPackages) {
            config.addUrls(ClasspathHelper.forPackage(pkg, classLoader));
        }

        Reflections reflections = new Reflections(config);

        Set<Class<?>> controllers = reflections.getTypesAnnotatedWith(RestController.class);

        for (Class<?> controllerClass : controllers) {
            List<String> classPaths = getPaths(controllerClass);

            for (Method method : controllerClass.getDeclaredMethods()) {
                Set<RequestMethod> requestMethods = determineRequestMethods(method);
                if (requestMethods.isEmpty()) continue;

                Operation operation = new Operation();

                if (controllerClass.isAnnotationPresent(io.swagger.v3.oas.annotations.tags.Tag.class)) {
                    io.swagger.v3.oas.annotations.tags.Tag tagAnn = controllerClass.getAnnotation(io.swagger.v3.oas.annotations.tags.Tag.class);
                    operation.addTagsItem(tagAnn.name());
                    addGlobalTag(oas, tagAnn);
                }

                processParameters(method, operation, components);
                processResponse(method, operation, components);

                List<String> methodPaths = getPaths(method);
                for (String classPath : classPaths) {
                    for (String methodPath : methodPaths) {
                        String fullPath = sanitizePath(classPath + methodPath);

                        PathItem pathItem = paths.getOrDefault(fullPath, new PathItem());

                        for (RequestMethod rm : requestMethods) {
                            setOperationByMethod(pathItem, rm, operation);
                        }
                        paths.addPathItem(fullPath, pathItem);
                    }
                }
            }
        }

        oas.setPaths(paths);
        return oas;
    }

    public OpenAPI localize(OpenAPI rawOas, I18nResolver i18n) throws JsonProcessingException {
        ObjectMapper mapper = Json.mapper();
        OpenAPI oas = mapper.readValue(mapper.writeValueAsString(rawOas), OpenAPI.class);

        localize(oas.getPaths(), i18n);
        localize(oas.getComponents().getSchemas(), i18n);

        if (oas.getTags() != null) {
            oas.getTags().forEach(tag -> {
                tag.setName(translate(tag.getName(), i18n));
                tag.setDescription(translate(tag.getDescription(), i18n));
            });
        }

        return oas;
    }

    private List<String> getPaths(AnnotatedElement element) {
        String[] paths = null;
        if (element.isAnnotationPresent(RequestMapping.class)) {
            paths = element.getAnnotation(RequestMapping.class).value();
        } else if (element.isAnnotationPresent(GetMapping.class)) {
            paths = element.getAnnotation(GetMapping.class).value();
        } else if (element.isAnnotationPresent(PostMapping.class)) {
            paths = element.getAnnotation(PostMapping.class).value();
        } else if (element.isAnnotationPresent(DeleteMapping.class)) {
            paths = element.getAnnotation(DeleteMapping.class).value();
        } else if (element.isAnnotationPresent(PutMapping.class)) {
            paths = element.getAnnotation(PutMapping.class).value();
        } else if (element.isAnnotationPresent(PatchMapping.class)) {
            paths = element.getAnnotation(PatchMapping.class).value();
        }

        if (paths == null || paths.length == 0) return Collections.singletonList("");
        return Arrays.asList(paths);
    }

    private Set<RequestMethod> determineRequestMethods(Method method) {
        Set<RequestMethod> result = new HashSet<>();
        if (method.isAnnotationPresent(GetMapping.class)) result.add(RequestMethod.GET);
        else if (method.isAnnotationPresent(PostMapping.class)) result.add(RequestMethod.POST);
        else if (method.isAnnotationPresent(PutMapping.class)) result.add(RequestMethod.PUT);
        else if (method.isAnnotationPresent(DeleteMapping.class)) result.add(RequestMethod.DELETE);
        else if (method.isAnnotationPresent(PatchMapping.class)) result.add(RequestMethod.PATCH);
        else if (method.isAnnotationPresent(RequestMapping.class)) {
            result.addAll(Arrays.asList(method.getAnnotation(RequestMapping.class).method()));
        }
        return result;
    }

    private String sanitizePath(String path) {
        if (path == null || path.isEmpty()) return "/";
        String result = path.replace("//", "/");
        if (!result.startsWith("/")) result = "/" + result;
        if (result.length() > 1 && result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }

    private void addGlobalTag(OpenAPI oas, io.swagger.v3.oas.annotations.tags.Tag tagAnn) {
        if (oas.getTags() == null) oas.setTags(new ArrayList<>());

        boolean exists = oas.getTags().stream()
                                 .anyMatch(t -> t.getName().equals(tagAnn.name()));

        if (!exists) {
            Tag tag = new Tag().name(tagAnn.name()).description(tagAnn.description());
            oas.addTagsItem(tag);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void localize(Schema schema, I18nResolver i18n) {
        if (schema == null) return;
        schema.setDescription(translate(schema.getDescription(), i18n));
        schema.setTitle(translate(schema.getTitle(), i18n));

        if (schema instanceof ArraySchema as && as.getItems() != null) {
            localize(as.getItems(), i18n);
        }

        if (schema.getProperties() != null) {
            ((Map<String, Schema>) schema.getProperties()).values().forEach(s -> localize(s, i18n));
        }
    }

    private void localize(Operation operation, I18nResolver i18n) {
        if (operation == null) return;

        operation.setSummary(translate(operation.getSummary(), i18n));
        operation.setDescription(translate(operation.getDescription(), i18n));

        if (operation.getParameters() != null) {
            for (Parameter p : operation.getParameters()) {
                p.setDescription(translate(p.getDescription(), i18n));

                if (p.getSchema() != null) {
                    localize(p.getSchema(), i18n);
                }
            }
        }

        if (operation.getRequestBody() != null && operation.getRequestBody().getContent() != null) {
            operation.getRequestBody().setDescription(translate(operation.getRequestBody().getDescription(), i18n));
            operation.getRequestBody().getContent().values().forEach(mediaType -> {
                if (mediaType.getSchema() != null) localize(mediaType.getSchema(), i18n);
            });
        }

        if (operation.getTags() != null) {
            List<String> translatedTags = operation.getTags().stream()
                                                  .map(t -> translate(t, i18n))
                                                  .collect(Collectors.toList());
            operation.setTags(translatedTags);
        }
    }

    private void localize(Paths paths, I18nResolver i18n) {
        if (paths == null) return;
        paths.values().forEach(pathItem -> pathItem.readOperations()
                                                   .forEach(p -> localize(p, i18n)));
    }

    @SuppressWarnings("rawtypes")
    private void localize(Map<String, Schema> schemas, I18nResolver i18n) {
        if (schemas == null) return;
        for (Schema<?> schema : schemas.values()) {
            localize(schema, i18n);
        }
    }

    @SuppressWarnings("rawtypes")
    private void processParameters(Method method, Operation op, Components components) {
        for (java.lang.reflect.Parameter mp : method.getParameters()) {
            if (mp.isAnnotationPresent(PathVariable.class)) {
                PathVariable pv = mp.getAnnotation(PathVariable.class);
                assert pv != null;
                op.addParametersItem(new Parameter()
                                             .name(pv.value().isEmpty() ? mp.getName() : pv.value())
                                             .in("path")
                                             .required(true)
                                             .schema(getSchema(mp.getParameterizedType(), components)));
            }
            else if (mp.isAnnotationPresent(RequestParam.class)) {
                RequestParam rp = mp.getAnnotation(RequestParam.class);
                assert rp != null;
                op.addParametersItem(new Parameter()
                                             .name(rp.value().isEmpty() ? mp.getName() : rp.value())
                                             .in("query")
                                             .required(rp.required())
                                             .schema(getSchema(mp.getParameterizedType(), components)));
            }
            else if (mp.isAnnotationPresent(RequestBody.class)) {
                Type type = mp.getParameterizedType();
                getSchema(type, components);

                String schemaName = mp.getType().getSimpleName();
                Schema refSchema = new Schema().$ref("#/components/schemas/" + schemaName);

                op.setRequestBody(new io.swagger.v3.oas.models.parameters.RequestBody()
                                          .content(new Content().addMediaType("application/json",
                                                  new MediaType().schema(refSchema))));
            } else if (isQueryDtoParameter(method, mp)) {
                expandDtoToQueryParameters(mp, op, components);
            }

            if (method.isAnnotationPresent(io.swagger.v3.oas.annotations.parameters.RequestBody.class)) {
                io.swagger.v3.oas.annotations.parameters.RequestBody ann =
                        method.getAnnotation(io.swagger.v3.oas.annotations.parameters.RequestBody.class);

                if (ann != null) {
                    io.swagger.v3.oas.models.parameters.RequestBody rb = op.getRequestBody();
                    if (op.getRequestBody() == null) {
                        rb = new io.swagger.v3.oas.models.parameters.RequestBody();
                    }

                    rb.setDescription(ann.description());
                    rb.setRequired(ann.required());

                    Content content = new Content();
                    for (io.swagger.v3.oas.annotations.media.Content cAnn : ann.content()) {
                        MediaType mt = new MediaType();

                        for (ExampleObject eoAnn : cAnn.examples()) {
                            Example example = new Example();
                            example.setSummary(eoAnn.summary());
                            example.setDescription(eoAnn.description());
                            example.setValue(eoAnn.value());

                            mt.addExamples(eoAnn.name(), example);
                        }

                        if (cAnn.schema().implementation() != Void.class) {
                            getSchema(cAnn.schema().implementation(), components);
                        }

                        content.addMediaType(cAnn.mediaType(), mt);
                    }

                    rb.setContent(content);
                    op.setRequestBody(rb);
                }
            }
        }
    }

    @SuppressWarnings("rawtypes")
    private void processResponse(Method method, Operation op, Components components) {
        Type returnType = method.getGenericReturnType();
        if (returnType.equals(Void.TYPE) || returnType.equals(Void.class)) {
            op.responses(new ApiResponses().addApiResponse("204", new ApiResponse().description("No Content")));
            return;
        }

        if (returnType instanceof ParameterizedType pt && pt.getRawType().equals(ResponseEntity.class)) {
            returnType = pt.getActualTypeArguments()[0];
        }

        Schema schema = getSchema(returnType, components);
        ApiResponse response = new ApiResponse()
                                       .description("OK")
                                       .content(new Content().addMediaType("application/json",
                                               new MediaType().schema(schema)));

        op.responses(new ApiResponses().addApiResponse("200", response));
    }

    private boolean isQueryDtoParameter(Method method, java.lang.reflect.Parameter mp) {
        if (!method.isAnnotationPresent(GetMapping.class)) {
            return false;
        }

        if (mp.isAnnotationPresent(PathVariable.class)) {
            return false;
        }

        if (mp.isAnnotationPresent(RequestParam.class)) {
            return false;
        }

        String typeName = mp.getType().getName();

        return !typeName.startsWith("java.servlet") &&
                       !typeName.startsWith("jakarta.servlet") &&
                       !typeName.startsWith("org.springframework.ui") &&
                       !typeName.startsWith("org.springframework.validation");
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void expandDtoToQueryParameters(java.lang.reflect.Parameter mp, Operation op, Components components) {
        ResolvedSchema resolvedSchema = ModelConverters.getInstance()
                                                .resolveAsResolvedSchema(new AnnotatedType(mp.getType()));

        if (resolvedSchema.schema != null && resolvedSchema.schema.getProperties() != null) {
            Map<String, Schema> properties = resolvedSchema.schema.getProperties();

            for (Map.Entry<String, Schema> entry : properties.entrySet()) {
                String name = entry.getKey();
                Schema propertySchema = entry.getValue();

                Parameter parameter = new Parameter()
                                              .name(name)
                                              .in("query")
                                              .description(propertySchema.getDescription())
                                              .required(isFieldRequired(propertySchema))
                                              .schema(propertySchema)
                                              .example(propertySchema.getExample());
                op.addParametersItem(parameter);
            }

            if (resolvedSchema.referencedSchemas != null) {
                resolvedSchema.referencedSchemas.forEach(components::addSchemas);
            }
        }
    }

    @SuppressWarnings("rawtypes")
    private Boolean isFieldRequired(Schema schema) {
        return schema.getRequired() != null && schema.getRequired().contains(schema.getName());
    }

    private String getTypeName(Type type) {
        if (type instanceof Class<?> clazz) {
            return clazz.getSimpleName();
        } else if (type instanceof ParameterizedType pt) {
            Type rawType = pt.getRawType();
            Type[] args = pt.getActualTypeArguments();
            if (rawType instanceof Class<?> && args.length > 0 && args[0] instanceof Class<?> argClass) {
                return argClass.getSimpleName();
            }
        }
        return "Object";
    }

    @SuppressWarnings("rawtypes")
    private Schema getSchema(Type type, Components components) {
        ResolvedSchema resolvedSchema = ModelConverters.getInstance().resolveAsResolvedSchema(new AnnotatedType(type));
        if (resolvedSchema.schema != null) {
            if (resolvedSchema.referencedSchemas != null) {
                resolvedSchema.referencedSchemas.forEach(components::addSchemas);
            }
            String schemaName = getTypeName(type);
            if (components.getSchemas() != null && components.getSchemas().containsKey(schemaName)) {
                return new Schema().$ref("#/components/schemas/" + schemaName);
            }

            return resolvedSchema.schema;
        }
        return new Schema().type("object");
    }

    private void setOperationByMethod(PathItem pi, RequestMethod rm, Operation op) {
        switch (rm) {
            case GET -> pi.setGet(op);
            case POST -> pi.setPost(op);
            case PUT -> pi.setPut(op);
            case DELETE -> pi.setDelete(op);
            case PATCH -> pi.setPatch(op);
        }
    }

    private String translate(String text, I18nResolver i18n) {
        if (text == null || text.isEmpty()) return text;

        if (text.startsWith("${") && text.endsWith("}")) {
            String key = text.substring(2, text.length() - 1);
            try {
                return i18n.t(key);
            } catch (MissingResourceException e) {
                return key;
            }
        }
        return text;
    }
}
