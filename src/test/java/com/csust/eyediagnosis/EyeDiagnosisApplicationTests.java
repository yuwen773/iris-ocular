package com.csust.eyediagnosis;

import com.csust.eyediagnosis.config.SameOriginFilter;
import com.csust.eyediagnosis.controlcenter.ControlCenterCsrfController;
import com.csust.eyediagnosis.controlcenter.security.ControlCenterCsrfFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.annotation.DirtiesContext;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
// Stop scheduled workers before another application fixture uses the same CI schema.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class EyeDiagnosisApplicationTests {

    @Autowired
    private ApplicationContext context;

    @Test
    void contextLoads() {
        // 控制中心默认关闭：全局 Origin 过滤器照旧存在，但严格模式的 CSRF
        // 过滤器与端点一枚都不注册——默认部署行为必须与引入前完全一致。
        assertEquals(1, context.getBeanNamesForType(SameOriginFilter.class).length);
        assertEquals(0, context.getBeanNamesForType(ControlCenterCsrfFilter.class).length);
        assertEquals(0, context.getBeanNamesForType(ControlCenterCsrfController.class).length);
    }

}
