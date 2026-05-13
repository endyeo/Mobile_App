package com.flower.backend.auth;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RestController
public class OAuthCallbackController {

    @GetMapping("/oauth/callback")
    public void kakaoCallback(
            @RequestParam String code,
            HttpServletResponse response) throws IOException {
        response.sendRedirect("ourt://oauth?code=" + code);
    }
}
