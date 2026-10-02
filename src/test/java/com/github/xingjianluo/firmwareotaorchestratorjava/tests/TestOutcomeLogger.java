package com.github.xingjianluo.firmwareotaorchestratorjava.tests;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestWatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 全局用例结果打点（ServiceLoader 自动注册，见
 * {@code src/test/resources/META-INF/services/org.junit.jupiter.api.extension.Extension}）。
 *
 * <p>每个用例结束都会在测试日志里打印一行「业务测试类#用例名 => 结果」，
 * 配合各用例内部打印的输入/判定日志，运行 {@code mvn test} 即可直接看出
 * 覆盖了哪些业务、每个用例的判定与最终结果。</p>
 */
public class TestOutcomeLogger implements TestWatcher {

    private static final Logger log = LoggerFactory.getLogger(TestOutcomeLogger.class);

    private static String caseName(ExtensionContext context) {
        return context.getTestClass().map(Class::getSimpleName).orElse("?")
                + "#" + context.getTestMethod().map(java.lang.reflect.Method::getName).orElse("?");
    }

    @Override
    public void testSuccessful(ExtensionContext context) {
        log.info("[用例结果] {} => 通过", caseName(context));
    }

    @Override
    public void testFailed(ExtensionContext context, Throwable cause) {
        log.error("[用例结果] {} => 失败: {}", caseName(context), cause.toString());
    }

    @Override
    public void testAborted(ExtensionContext context, Throwable cause) {
        log.warn("[用例结果] {} => 中止: {}", caseName(context), cause.toString());
    }

    @Override
    public void testDisabled(ExtensionContext context, java.util.Optional<String> reason) {
        log.info("[用例结果] {} => 跳过: {}", caseName(context), reason.orElse("未说明"));
    }
}
