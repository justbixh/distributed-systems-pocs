package com.example.wspoc.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the single-page chat client at the root URL.
 */
@Controller
public class PageController {

    @GetMapping("/")
    public String index() {
        return "index";   // → src/main/resources/templates/index.html
    }
}
