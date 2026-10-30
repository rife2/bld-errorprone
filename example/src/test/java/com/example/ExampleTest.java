package com.example;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ExampleTest {

    @Test
    void verifyHello() {
        assertEquals("Hello World!", new Example().getMessage());
    }
}
