package com.kc1vmz.netcentral.controller;

import com.kc1vmz.netcentral.accessor.DataMaintenanceAccessor;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

@Controller
public class DataController {
    private final DataMaintenanceAccessor data;
    public DataController(DataMaintenanceAccessor data) { this.data = data; }

    @GetMapping("/setup/data")
    public String page() { data.requireSysadmin(); return "data-settings"; }

    @DeleteMapping({"/api/v1/data/aprs", "/api/v1/APRSObjects/all/now"})
    @ResponseBody
    public void aprs() { data.deleteAprs(); }

    @DeleteMapping("/api/v1/data/nets")
    @ResponseBody
    public void nets() { data.deleteNets(); }
}
