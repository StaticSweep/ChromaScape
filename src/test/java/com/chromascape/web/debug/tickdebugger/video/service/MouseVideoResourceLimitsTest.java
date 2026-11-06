package com.chromascape.web.debug.tickdebugger.video.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.chromascape.web.debug.tickdebugger.video.MouseVideoProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/** Real Linux cgroup controls, rather than assertions about configured command arguments. */
@EnabledOnOs(OS.LINUX)
class MouseVideoResourceLimitsTest {
  private MouseVideoProperties properties() {
    MouseVideoProperties properties = new MouseVideoProperties();
    properties.setMaxRssBytes(64L * 1024 * 1024);
    properties.setCpuQuotaPercent(37);
    properties.setTasksMax(11);
    return properties;
  }

  @Test
  void effectiveCgroupLimitsMatchTheConfiguredBudget() throws Exception {
    var result =
        new MouseVideoProcessRunner(properties())
            .run(
                List.of(
                    "/usr/bin/python3",
                    "-c",
                    "import pathlib,json; p=pathlib.Path('/sys/fs/cgroup'+pathlib.Path('/proc/self/cgroup').read_text().strip().split('::')[1]); "
                        + "print(json.dumps({f:(p/f).read_text().strip() for f in ('memory.max','memory.swap.max','cpu.max','pids.max')}))"),
                10_000,
                new MouseVideoCancellation(),
                null,
                4096);
    assertThat(result.exitCode()).isZero();
    var limits = new ObjectMapper().readTree(result.stdout());
    assertThat(limits.path("memory.max").asText()).isEqualTo("67108864");
    assertThat(limits.path("memory.swap.max").asText())
        .as("the memory budget is a total ceiling, not a resident-only ceiling")
        .isEqualTo("0");
    assertThat(limits.path("pids.max").asText()).isEqualTo("11");
    String[] cpu = limits.path("cpu.max").asText().split(" ");
    assertThat(Double.parseDouble(cpu[0]) / Double.parseDouble(cpu[1])).isBetween(.36, .38);
  }

  @Test
  void allocationAboveMemoryBudgetIsKilled() throws Exception {
    var result =
        new MouseVideoProcessRunner(properties())
            .run(
                List.of(
                    "/usr/bin/python3",
                    "-c",
                    "a=bytearray(256*1024*1024); print('unbounded allocation succeeded')"),
                10_000,
                new MouseVideoCancellation(),
                null,
                4096);
    assertThat(result.exitCode()).isNotZero();
    assertThat(result.stdout()).doesNotContain("unbounded allocation succeeded");
    assertThat(result.outputComplete()).isTrue();
  }

  @Test
  void processBudgetRefusesAdditionalChildrenAndCleanupStopsExistingChildren() throws Exception {
    var result =
        new MouseVideoProcessRunner(properties())
            .run(
                List.of(
                    "/usr/bin/python3",
                    "-c",
                    "import subprocess; children=[]\ntry:\n for n in range(40): children.append(subprocess.Popen(['/bin/sleep','30']))\n"
                        + "except OSError: print('bounded',len(children),flush=True)\n"),
                10_000,
                new MouseVideoCancellation(),
                null,
                4096);
    assertThat(result.exitCode()).isZero();
    assertThat(result.stdout()).startsWith("bounded ");
    assertThat(result.outputComplete()).isTrue();
  }
}
