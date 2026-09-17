package kit.penny.clientbus.server.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class SpaController {

    @GetMapping({
            "/inbox",
            "/inbox/{*path}",

            "/clients",
            "/clients/{*path}",

            "/channels",
            "/channels/{*path}",

            "/employees",
            "/employees/{*path}",

            "/workspaces",
            "/workspaces/{*path}",

            "/settings",
            "/settings/{*path}"
    })
    public String forwardToIndex() {
        return "forward:/index.html";
    }
}