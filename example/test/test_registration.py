#!/usr/bin/env python3
"""
example-jssip SIP 注册功能全场景测试脚本。

测试重点：
1. 坐席能正常注册成功（状态切换为在线）。
2. 点击呼叫按钮提示"测试示例，仅支持注册测试"。
3. 对 example-1-java 与 example-2-java 两个后端各跑 5 轮，共 10 轮，全部稳定通过。

运行前提：
- example-jssip 前端运行在 http://localhost:5173
- example-1-java 后端运行在 8081（SIP over WS 路径 /sipproxy/ws，token=test）
- example-2-java 后端运行在 8082（SIP over WS 路径 /sipproxy/ws，token=test-token）
"""
import os
import sys
import time
from playwright.sync_api import sync_playwright, Page, TimeoutError as PlaywrightTimeoutError

# ====================================================================
# 测试配置
# ====================================================================
BACKENDS = [
    {
        "name": "example-1-java",
        "ws_url": "ws://localhost:8081/sipproxy/ws?token=test",
        "sip_domain": "sipproxy.example",
        "extension": "1001",
        "password": "123456",
    },
    {
        "name": "example-2-java",
        "ws_url": "ws://localhost:8082/sipproxy/ws?token=test-token",
        "sip_domain": "sipproxy.example",
        "extension": "1001",
        "password": "123456",
    },
]
ROUNDS_PER_BACKEND = 5
FRONTEND_URL = "http://localhost:5173"

# 失败截图与脚本同目录，便于聚合排查
SCREENSHOT_DIR = os.path.dirname(os.path.abspath(__file__))

# 呼叫按钮提示文案（与前端实现保持一致）
DIAL_PROMPT_TEXT = "测试示例，仅支持注册测试"


def log(msg: str) -> None:
    """带时间戳的日志输出，便于追踪每一步执行状态。"""
    print(f"[{time.strftime('%Y-%m-%d %H:%M:%S')}] {msg}")


def fill_input(page: Page, testid: str, value: str, timeout: int = 5000) -> None:
    """
    兼容 Element Plus 渲染结构：data-testid 可能位于外层 div，也可能直接挂在 input 上。
    优先尝试 `[data-testid=xxx] input`（短超时 800ms 快速失败），失败后回退到 `[data-testid=xxx]` 本身。
    填写前先清空，避免上一轮残留值污染本轮配置。
    """
    inner = page.locator(f'[data-testid={testid}] input')
    outer = page.locator(f'[data-testid={testid}]')
    try:
        # 内层 input 短超时探测，命中即用；不命中快速回退到外层，避免每字段浪费 5s
        inner.wait_for(state="visible", timeout=800)
        inner.click()
        inner.fill(value)
        log(f"  填写 {testid}={value}（命中内层 input）")
    except Exception:
        outer.wait_for(state="visible", timeout=timeout)
        outer.click()
        outer.fill(value)
        log(f"  填写 {testid}={value}（命中外层元素）")


def click_element(page: Page, testid: str, timeout: int = 10000) -> None:
    """点击 data-testid 元素，等待其可见后触发点击。"""
    el = page.locator(f'[data-testid={testid}]')
    el.wait_for(state="visible", timeout=timeout)
    # 使用 force=False 默认行为；若元素被遮挡会自动等待可点击
    el.click()
    log(f"  点击 [data-testid={testid}]")


def wait_for_element(page: Page, testid: str, timeout: int = 15000) -> None:
    """等待 data-testid 元素出现（visible），超时抛出异常。"""
    page.locator(f'[data-testid={testid}]').wait_for(state="visible", timeout=timeout)


def wait_for_text(page: Page, text: str, timeout: int = 5000) -> None:
    """
    等待页面出现指定文本。ElMessage 提示是动态渲染的，使用 text 选择器等待。
    失败时抛出 PlaywrightTimeoutError，由调用方截图保存。
    """
    page.locator(f"text={text}").wait_for(state="visible", timeout=timeout)


def run_one_round(page: Page, backend: dict, round_num: int) -> None:
    """执行一轮完整的注册测试流程，任意步骤失败均抛出异常。"""
    log(f"=== {backend['name']} 第 {round_num} 轮开始 ===")

    # 1. 访问前端页面
    log(f"  访问前端: {FRONTEND_URL}")
    page.goto(FRONTEND_URL, wait_until="domcontentloaded")

    # 2. 等待配置区加载完成（WS 输入框出现即代表页面就绪）
    log("  等待页面配置区加载...")
    wait_for_element(page, "ws-url", timeout=10000)

    # 3. 填写配置（每项先清空再写入，保证多轮上下文隔离）
    log("  填写配置...")
    fill_input(page, "ws-url", backend["ws_url"])
    fill_input(page, "sip-domain", backend["sip_domain"])
    fill_input(page, "extension", backend["extension"])
    fill_input(page, "password", backend["password"])

    # 4. 点击签入按钮，触发 JsSIP REGISTER 流程
    log("  点击签入按钮...")
    click_element(page, "login-btn", timeout=10000)

    # 5. 等待在线状态出现（注册成功），超时 15 秒
    log("  等待注册成功（在线状态出现，超时 15s）...")
    try:
        wait_for_element(page, "status-online", timeout=15000)
    except PlaywrightTimeoutError:
        raise AssertionError("注册超时：15 秒内未出现在线状态 status-online")
    log("  注册成功，状态切换为在线")

    # 6. 点击拨打入口按钮，打开拨号弹窗
    log("  点击拨打入口按钮...")
    click_element(page, "dial-btn", timeout=10000)

    # 7. 等待拨打提交按钮出现，确认弹窗已渲染
    log("  等待拨号弹窗渲染（dial-submit 出现）...")
    try:
        wait_for_element(page, "dial-submit", timeout=5000)
    except PlaywrightTimeoutError:
        raise AssertionError("拨号弹窗未渲染：5 秒内未出现 dial-submit 按钮")

    # 8. 点击拨打提交按钮，应触发 ElMessage 提示
    log("  点击拨打提交按钮...")
    click_element(page, "dial-submit", timeout=5000)

    # 9. 等待 ElMessage 出现"测试示例，仅支持注册测试"提示
    #    精确定位 .el-message__content，避免操作日志面板中的同名文本导致 strict mode 冲突
    log(f"  等待呼叫提示文案出现: {DIAL_PROMPT_TEXT}")
    try:
        page.locator('.el-message__content', has_text=DIAL_PROMPT_TEXT).wait_for(
            state="visible", timeout=5000
        )
    except PlaywrightTimeoutError:
        raise AssertionError(f'未出现呼叫提示文案: {DIAL_PROMPT_TEXT}')
    log("  呼叫提示文案验证通过")

    # 10. 点击签入按钮进行签出（同一按钮在在线态下作为签出入口）
    log("  点击签入按钮（执行签出）...")
    click_element(page, "login-btn", timeout=10000)

    # 11. 等待离线状态出现，确认签出成功
    log("  等待签出成功（离线状态出现，超时 10s）...")
    try:
        wait_for_element(page, "status-offline", timeout=10000)
    except PlaywrightTimeoutError:
        raise AssertionError("签出超时：10 秒内未出现离线状态 status-offline")
    log("  签出成功，状态切换为离线")

    log(f"=== {backend['name']} 第 {round_num} 轮通过 ===")


def main() -> None:
    """主入口：遍历后端 × 轮次执行测试，统计通过率并以 exit code 反馈结果。"""
    total = 0
    passed = 0

    with sync_playwright() as p:
        # 无头 Chromium，避免干扰用户桌面
        browser = p.chromium.launch(headless=True)
        try:
            for backend in BACKENDS:
                for round_num in range(1, ROUNDS_PER_BACKEND + 1):
                    total += 1
                    # 每轮新建 context，隔离 cookie/localStorage，避免上一轮残留状态干扰
                    context = browser.new_context()
                    page = context.new_page()
                    try:
                        run_one_round(page, backend, round_num)
                        passed += 1
                        print(f"[PASS] {backend['name']} 第 {round_num} 轮")
                    except Exception as e:
                        # 失败时截图保存到 test 目录，文件名包含后端名与轮次
                        screenshot_path = os.path.join(
                            SCREENSHOT_DIR,
                            f"screenshot_{backend['name']}_round{round_num}.png",
                        )
                        try:
                            page.screenshot(path=screenshot_path, full_page=True)
                        except Exception:
                            # 截图本身失败不影响测试结论输出
                            pass
                        print(f"[FAIL] {backend['name']} 第 {round_num} 轮: {e}")
                        print(f"  截图已保存: {screenshot_path}")
                    finally:
                        context.close()
                    # 轮次之间间隔 1 秒，避免 WS 端口未及时释放导致下一轮握手失败
                    time.sleep(1)
        finally:
            browser.close()

    print(f"\n===== 测试结果: {passed}/{total} 通过 =====")
    if passed < total:
        sys.exit(1)


if __name__ == "__main__":
    main()
