package org.ovirt.engine.core.common.utils;

import static org.junit.Assert.assertEquals;

import java.util.Set;

import javax.validation.ConstraintViolation;
import javax.validation.Validator;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.ovirt.engine.core.common.validation.annotation.ValidName;

@RunWith(Parameterized.class)
public class ValidNameValidatorTest {

    private Validator validator;
    @Parameterized.Parameter(0)
    public String name;
    @Parameterized.Parameter(1)
    public boolean expectedResult;

    @Before
    public void setup() throws Exception {
        validator = ValidationUtils.getValidator();
    }

    @Test
    public void checkName() {
        ValidNameContainer container = new ValidNameContainer(name);
        Set<ConstraintViolation<ValidNameContainer>> result = validator.validate(container);
        assertEquals("Failed to validate name: " + container.getName(), expectedResult, result.isEmpty());
    }

    @Parameterized.Parameters
    public static Object[][] namesParams() {
        return new Object[][] {
                { "abc", true },
                { "123", true },
                { "abc123", true },
                { "123abc", true },
                { null, true },
                { " ", false },
                { "", false },
                { "abc ", false },
                { " abc", false },
                { "abc cde", false },
                { "abc*", false }
        };
    }

    private static class ValidNameContainer {

        @ValidName
        private String name;

        public ValidNameContainer(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }
    }
}
