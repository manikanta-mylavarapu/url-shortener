package com.example.shortener.service;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CodeGeneratorTest {

    @Test
    void generatesBase62CodesOfConfiguredLength() {
        CodeGenerator generator = new CodeGenerator(7, new Random(42));
        for (int i = 0; i < 1_000; i++) {
            assertThat(generator.generate()).matches("[0-9A-Za-z]{7}");
        }
    }

    @Test
    void codesAreEffectivelyUniqueAtScale() {
        CodeGenerator generator = new CodeGenerator(7, new SecureRandom());
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 20_000; i++) {
            codes.add(generator.generate());
        }
        // 62^7 ~ 3.5e12 space: birthday collisions in 20k draws are astronomically unlikely.
        assertThat(codes).hasSize(20_000);
    }

    @Test
    void sameSeedGivesSameSequence_soTestsCanBeDeterministic() {
        assertThat(new CodeGenerator(8, new Random(7)).generate())
                .isEqualTo(new CodeGenerator(8, new Random(7)).generate());
    }

    @Test
    void rejectsUnsafeLengths() {
        assertThatThrownBy(() -> new CodeGenerator(3, new Random())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CodeGenerator(33, new Random())).isInstanceOf(IllegalArgumentException.class);
    }
}
