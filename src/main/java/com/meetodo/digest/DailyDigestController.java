package com.meetodo.digest;

import java.time.LocalDate;
import com.meetodo.config.DevEndpointsCondition;
import org.springframework.context.annotation.Conditional;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestController
@Profile("local | docker")
@Conditional(DevEndpointsCondition.class)
@RequestMapping("/api/dev/digest")
public class DailyDigestController {
    private final DailyDigestService service;
    public DailyDigestController(DailyDigestService service) { this.service = service; }

    @PostMapping("/run")
    public DailyDigestResponse run(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                   @RequestParam(defaultValue = "false") boolean force) {
        return service.run(date, force);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<?> invalidParameter(MethodArgumentTypeMismatchException exception) {
        return ResponseEntity.badRequest().body(Map.of("code", "VALIDATION_ERROR", "message", "Invalid query parameter.",
                "fieldErrors", Map.of(exception.getName(), "날짜는 yyyy-MM-dd, force는 true/false 형식입니다.")));
    }
}
