package com.example;

import java.util.Set;
import java.util.HashSet;

public class Example {

    public static void main(String[] args) {
        System.out.println(new Example().getMessage());
    }

    public void error() {
        var s = new HashSet<Short>();
        for (short i = 0; i < 100; i++) {
            s.add(i);
            s.remove(i - 1); // trigger error
        }
        System.out.println(s.size());
    }

    public String getMessage() {
        return "Hello World!";
    }

    public void ignored() {
        String[] colors = {"White", "Black"};
        System.out.println(colors); // ArrayToString ignored

    }

    public void warning() {
        String[] colors = {"Red", "Green", "Blue"}; // trigger warning
    }
}