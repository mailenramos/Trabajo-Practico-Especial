package ticketya.web.dto;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Arrays;

/** El texto debe ser el nombre de una constante del enum indicado (null se acepta; usar @NotBlank aparte). */
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ValorDeEnum.Validador.class)
public @interface ValorDeEnum {

    Class<? extends Enum<?>> value();

    String message() default "valor desconocido";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validador implements ConstraintValidator<ValorDeEnum, String> {

        private Enum<?>[] constantes;

        @Override
        public void initialize(ValorDeEnum anotacion) {
            constantes = anotacion.value().getEnumConstants();
        }

        @Override
        public boolean isValid(String valor, ConstraintValidatorContext contexto) {
            return valor == null || Arrays.stream(constantes).anyMatch(c -> c.name().equals(valor));
        }
    }
}
