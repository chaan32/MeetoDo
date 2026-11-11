package com.meetodo;

import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.Set;
import java.util.stream.Collectors;

/** Rejects unknown top-level fields only while MVC reads application request DTOs. */
@ControllerAdvice(annotations = Controller.class)
public class StrictHttpRequestBodyAdvice extends RequestBodyAdviceAdapter {
    private final ObjectMapper mapper;

    public StrictHttpRequestBodyAdvice(ObjectMapper mapper) { this.mapper = mapper; }

    @Override
    public boolean supports(MethodParameter parameter, Type targetType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        return MappingJackson2HttpMessageConverter.class.isAssignableFrom(converterType);
    }

    @Override
    public HttpInputMessage beforeBodyRead(HttpInputMessage input, MethodParameter parameter, Type targetType,
                                           Class<? extends HttpMessageConverter<?>> converterType)
            throws IOException {
        byte[] body = input.getBody().readAllBytes();
        Class<?> bodyClass = org.springframework.core.ResolvableType.forType(targetType).resolve();
        if (bodyClass != null && !bodyClass.isInterface() && !bodyClass.isRecord()
                && !bodyClass.getPackageName().startsWith("com.meetodo")) {
            return wrapped(input, body);
        }
        if (bodyClass != null && bodyClass.getPackageName().startsWith("com.meetodo")) {
            JsonNode root = mapper.readTree(body);
            if (root != null && root.isObject()) {
                BeanDescription description = mapper.getDeserializationConfig().introspect(mapper.constructType(bodyClass));
                Set<String> known = description.findProperties().stream().map(p -> p.getName()).collect(Collectors.toSet());
                var fields = root.fieldNames();
                while (fields.hasNext()) {
                    String field = fields.next();
                    if (!known.contains(field)) {
                        throw new HttpMessageNotReadableException("Unknown request field: " + field,
                                new IllegalArgumentException("Unknown request field: " + field), input);
                    }
                }
            }
        }
        return wrapped(input, body);
    }

    private HttpInputMessage wrapped(HttpInputMessage original, byte[] body) {
        return new HttpInputMessage() {
            @Override public java.io.InputStream getBody() { return new ByteArrayInputStream(body); }
            @Override public org.springframework.http.HttpHeaders getHeaders() { return original.getHeaders(); }
        };
    }
}
