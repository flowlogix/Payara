/*
 DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 Copyright (c) 2016 Payara Foundation. All rights reserved.
 The contents of this file are subject to the terms of the Common Development
 and Distribution License("CDDL") (collectively, the "License").  You
 may not use this file except in compliance with the License.  You can
 obtain a copy of the License at
 https://glassfish.dev.java.net/public/CDDL+GPL_1_1.html
 or packager/legal/LICENSE.txt.  See the License for the specific
 language governing permissions and limitations under the License.
 When distributing the software, include this License Header Notice in each
 file and include the License file at packager/legal/LICENSE.txt.
 */
package fish.payara.nucleus.healthcheck.preliminary;

import fish.payara.nucleus.healthcheck.HealthCheckResult;
import fish.payara.nucleus.healthcheck.HealthCheckResultEntry;
import fish.payara.nucleus.healthcheck.HealthCheckResultStatus;
import fish.payara.nucleus.healthcheck.HealthCheckWithThresholdExecutionOptions;
import fish.payara.nucleus.healthcheck.configuration.MachineMemoryUsageChecker;
import org.glassfish.api.StartupRunLevel;
import org.glassfish.hk2.runlevel.RunLevel;
import org.jvnet.hk2.annotations.Service;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.DecimalFormat;
import java.util.List;

/**
 * @author mertcaliskan
 */
@Service(name = "healthcheck-machinemem")
@RunLevel(StartupRunLevel.VAL)
public class MachineMemoryUsageHealthCheck extends BaseThresholdHealthCheck<HealthCheckWithThresholdExecutionOptions, MachineMemoryUsageChecker> {

    private static final String MEMTOTAL = "MemTotal:";
    private static final String MEMFREE = "MemFree:";
    private static final String MEMAVAILABLE = "MemAvailable:";
    private static final String ACTIVEFILE = "Active(file):";
    private static final String INACTIVEFILE = "Inactive(file):";
    private static final String RECLAIMABLE = "SReclaimable:";
    private static final String KB = "kB";

    @PostConstruct
    void postConstruct() {
        postConstruct(this, MachineMemoryUsageChecker.class);
    }

    @Override
    public HealthCheckWithThresholdExecutionOptions constructOptions(MachineMemoryUsageChecker checker) {
        return super.constructThresholdOptions(checker);
    }

    @Override
    public HealthCheckResult doCheck() {
        HealthCheckResult result = new HealthCheckResult();

        long memAvailable = 0;
        long memFree = 0;
        long memTotal = 0;
        long memActiveFile = 0;
        long memInactiveFile = 0;
        long memReclaimable= 0;

        boolean memAvailableFound = false;

        if (isLinux()) {
            try {
                List<String> lines = Files.readAllLines(Paths.get("/proc/meminfo"), StandardCharsets.UTF_8);
                if (lines.isEmpty()) {
                    return result;
                }

                for (String line : lines) {
                    String[] parts = line.split("\\s+");

                    if (parts.length > 1) {
                        String part = parts[0];
                        if (MEMAVAILABLE.equals(part)) {
                            memAvailable = parseMemInfo(parts);
                            memAvailableFound = true;
                        }
                        if (MEMFREE.equals(part)) {
                            memFree = parseMemInfo(parts);
                        }
                        if (MEMTOTAL.equals(part)) {
                            memTotal = parseMemInfo(parts);
                        }
                        if (ACTIVEFILE.equals(part)) {
                            memActiveFile = parseMemInfo(parts);
                        }
                        if (INACTIVEFILE.equals(part)) {
                            memInactiveFile = parseMemInfo(parts);
                        }
                        if (RECLAIMABLE.equals(part)) {
                            memReclaimable = parseMemInfo(parts);
                        }
                    }
                }

                if (!memAvailableFound) {
                    memAvailable = memFree + memActiveFile + memInactiveFile + memReclaimable;
                }

                double usedPercentage = ((double) memAvailable / memTotal) * 100;

                result.add(new HealthCheckResultEntry(decideOnStatusWithRatio(usedPercentage),
                        "Physical Memory Used: " + prettyPrintBytes(memTotal - memAvailable) + " - " +
                                "Total Physical Memory: " + prettyPrintBytes(memTotal) + " - " +
                                "Memory Used%: " + new DecimalFormat("#.00").format(usedPercentage) + "%"));

            } catch (IOException exception) {
                result.add(new HealthCheckResultEntry(HealthCheckResultStatus.CHECK_ERROR,
                        "Memory information cannot be read for retrieving physical memory usage values",
                        exception));
            } catch (ArithmeticException exception) {
                result.add(new HealthCheckResultEntry(HealthCheckResultStatus.CHECK_ERROR,
                        "Error occurred while calculating memory usage values. Total memory is " + memTotal,
                        exception));
            }
        }
        else {
            try {
                OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
                Long totalPhysicalMemSize = invokeMethodFor(osBean, "getTotalPhysicalMemorySize");
                Long freePhysicalMemSize = invokeMethodFor(osBean, "getFreePhysicalMemorySize");

                double usedPercentage = ((double) (totalPhysicalMemSize - freePhysicalMemSize) / totalPhysicalMemSize) *
                        100;

                result.add(new HealthCheckResultEntry(decideOnStatusWithRatio(usedPercentage),
                        "Physical Memory Used: " + prettyPrintBytes((totalPhysicalMemSize - freePhysicalMemSize)) + " - " +
                                "Total Physical Memory: " + prettyPrintBytes(totalPhysicalMemSize) + " - " +
                                "Memory Used%: " + new DecimalFormat("#.00").format(usedPercentage) + "%"));

            } catch (Exception exception) {
                result.add(new HealthCheckResultEntry(HealthCheckResultStatus.CHECK_ERROR,
                        "Operating system methods cannot be invoked for retrieving physical memory usage values",
                        exception));
            }
        }

        return result;
    }

    private boolean isLinux() {
        String osName = System.getProperty("os.name");
        if (osName.startsWith("Linux") ||
                osName.startsWith("FreeBSD") ||
                osName.startsWith("OpenBSD") ||
                osName.startsWith("gnu") ||
                osName.startsWith("gnu/kfreebsd") ||
                osName.startsWith("netbsd")) {
            return true;
        }
        return false;
    }

    private long parseMemInfo(String[] parts) {
        long memory = 0;

        if (parts.length >= 2) {
            memory = Long.parseLong(parts[1]);
            if (parts.length > 2 && KB.equals(parts[2])) {
                memory *= 1024;
            }
        }
        return memory;
    }

    private Long invokeMethodFor(OperatingSystemMXBean osBean, String methodName) throws Exception {
        Method m = osBean.getClass().getDeclaredMethod(methodName);
        m.setAccessible(true);
        return (Long) m.invoke(osBean);
    }
}

