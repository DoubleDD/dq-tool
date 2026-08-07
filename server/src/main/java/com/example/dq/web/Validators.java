package com.example.dq.web;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;

import java.util.Set;

/**
 * 入参校验工具:替代 Spring MVC 的 @Valid 触发。
 * 消息格式与改造前 GlobalExceptionHandler 的 MethodArgumentNotValid 分支一致:"字段名 校验消息"。
 */
public final class Validators {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    private Validators() {
    }

    public static <T> T validate(T bean) {
        Set<ConstraintViolation<T>> violations = VALIDATOR.validate(bean);
        if (!violations.isEmpty()) {
            ConstraintViolation<T> violation = violations.iterator().next();
            throw new ValidationException(violation.getPropertyPath() + " " + violation.getMessage());
        }
        return bean;
    }
}
