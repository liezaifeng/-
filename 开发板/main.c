#include <string.h>
#include <stdint.h>
#include <stdbool.h>
#include <stddef.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "esp_log.h"
#include "nvs_flash.h"
#include "nvs.h"
#include "esp_adc/adc_oneshot.h"

/* NimBLE 头文件 */
#include "host/ble_hs.h"
#include "host/ble_store.h"
#include "services/gap/ble_svc_gap.h"
#include "services/gatt/ble_svc_gatt.h"
#include "nimble/nimble_port.h"
#include "nimble/nimble_port_freertos.h"
#include "store/config/ble_store_config.h"

/* 本版本 IDF 的 ble_store_config.h 中没有此声明（官方 bleprph 示例同样是自行声明） */
void ble_store_config_init(void);

/* ======================= 硬件配置 ======================= */
/*
 * 油门信号经过 10k:10k 分压后接 GPIO3（ADC1_CH3），GPIO0 空置。
 * 分压只是把油门信号电压降到 ADC 量程内，标定在 ADC 原始码域内做比例
 * 换算，分压对映射无影响。
 *
 * !!! 不要把油门信号接到 GPIO2 !!!
 * ESP32-C3 的 GPIO2 是 strapping 引脚，复位时必须为高电平；油门怠速输出
 * 电压偏低，接 GPIO2 后每次复位都可能使芯片进入下载模式，程序无法运行。
 */
#define ADC_GPIO            GPIO_NUM_3
#define ADC_CHANNEL         ADC_CHANNEL_3
#define ADC_ATTEN           ADC_ATTEN_DB_12
#define ADC_WIDTH           ADC_BITWIDTH_12

/* ======================= 动态映射参数 ======================= */
/* 完全手动标定：最小值、最大值都由手机 App 按钮记录（写入 NVS 永久保存）。
 * 映射完全在原始码域内做比例换算，不再依赖任何"满量程电压"假设。 */
#define MAP_EXTEND_LOW      -1
#define MAP_EXTEND_HIGH     260
#define EMA_ALPHA           0.60f

/* ======================= BLE 配置 ======================= */
#define DEVICE_NAME         "模拟声浪1.0"
#define APP_ADV_FLAGS       (BLE_HS_ADV_F_DISC_GEN | BLE_HS_ADV_F_BREDR_UNSUP)

/* ======================= 挡位参数 ======================= */
#define HOLD_GEAR1_MS   500
#define HOLD_GEAR2_MS   800
#define HOLD_GEAR3_MS   800
#define HOLD_GEAR4_MS   800

#define DOWN_SHIFT_HOLD_MS  800  /* 降挡保持时间（升挡用上面的长保持） */

/* ======================= 方向帽（Hat switch）定义 ======================= */
/* 换挡上/下键改用游戏手柄方向帽（D-pad）：安卓将上/下映射为 DPAD_UP / DPAD_DOWN，
 * 与键盘方向键等价，但不再声明 Keyboard 页，避免手机把它当实体键盘而隐藏软键盘。 */
#define HAT_UP      0
#define HAT_DOWN    4
#define HAT_NULL    8

/* ======================= HID 报告结构 ======================= */
/* 2 字节报告格式（无 Report ID 前缀，Report 特征也不带 0x2908 描述符）：
 *   字节 0 低 4 位  按钮 1~4
 *   字节 0 高 4 位  方向帽（上=0 / 下=4 / 空=8）
 *   字节 1          RT 线性油门 0~255（Simulation Controls 页 Usage 0xC4 Accelerator，
 *                   Android 映射为 AXIS_GAS 触发轴） */
typedef struct __attribute__((packed)) {
    uint8_t buttons_hat;          /* 低 4 位按钮 + 高 4 位方向帽 */
    uint8_t accelerator;          /* RT 线性油门：0~255 */
} gamepad_report_t;

static const uint8_t hid_descriptor[] = {
    0x05, 0x01, 0x09, 0x05, 0xa1, 0x01,   /* Generic Desktop 页，Gamepad 应用集合 */
    0x05, 0x09, 0x19, 0x01, 0x29, 0x04,    /* 按钮 1~4 */
    0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x04, 0x81, 0x02,
    /* 方向帽 Hat switch（4 位，含 Null State） */
    0x05, 0x01, 0x09, 0x39,
    0x15, 0x00, 0x25, 0x07,
    0x35, 0x00, 0x46, 0x3B, 0x01,
    0x65, 0x14,
    0x75, 0x04, 0x95, 0x01, 0x81, 0x42,
    0x05, 0x02, 0x09, 0xC4,                /* Simulation Controls 页，Accelerator */
    0x15, 0x00, 0x26, 0xFF, 0x00,          /* 逻辑范围 0~255 */
    0x75, 0x08, 0x95, 0x01, 0x81, 0x02,   /* 1 字节 RT */
    0xc0
};

/* ======================= GATT 服务 UUID ======================= */
static const ble_uuid16_t hid_svc_uuid = BLE_UUID16_INIT(0x1812);
static const ble_uuid16_t hid_info_char_uuid = BLE_UUID16_INIT(0x2A4A);
static const ble_uuid16_t report_map_char_uuid = BLE_UUID16_INIT(0x2A4B);
static const ble_uuid16_t protocol_mode_char_uuid = BLE_UUID16_INIT(0x2A4E);
static const ble_uuid16_t report_char_uuid = BLE_UUID16_INIT(0x2A4D);
static const uint8_t hid_info_value[] = {0x11, 0x01, 0x00, 0x02};

/* 自定义标定服务（手机 App 写入"记录油门最大/最小值"命令） */
static const ble_uuid16_t calib_svc_uuid  = BLE_UUID16_INIT(0xFFE0);
static const ble_uuid16_t calib_char_uuid = BLE_UUID16_INIT(0xFFE1);
static const ble_uuid16_t calib_status_char_uuid = BLE_UUID16_INIT(0xFFE2);
/* 车速特征值（App 写入当前车速 KM/H）+ 换挡开关特征值（App 读/写，永久保存） */
static const ble_uuid16_t speed_char_uuid = BLE_UUID16_INIT(0xFFE3);
static const ble_uuid16_t shift_char_uuid = BLE_UUID16_INIT(0xFFE4);
/* 油门通知特征值（开发板 notify 当前油门 0~255 给后台 App）+ 通道模式特征值（App 写 0=HID / 1=GATT） */
static const ble_uuid16_t throttle_char_uuid = BLE_UUID16_INIT(0xFFE5);
static const ble_uuid16_t mode_char_uuid = BLE_UUID16_INIT(0xFFE6);

/* ======================= 调试日志 ======================= */
static const char *TAG = "gamepad";
/* 全局关闭日志，但单独开放本模块 INFO 级日志用于排查（sdkconfig 上限恰为 INFO） */

/* ======================= 全局变量 ======================= */
static volatile uint16_t conn_handle = BLE_HS_CONN_HANDLE_NONE;
static volatile bool notify_enabled = false;       /* 手机是否已订阅通知 */
static uint16_t report_val_handle;
static uint16_t calib_char_handle;
static uint16_t calib_status_char_handle;
static uint16_t speed_char_handle;
static uint16_t shift_char_handle;
static uint16_t throttle_char_handle;
static uint16_t mode_char_handle;
static volatile bool throttle_notify_enabled = false; /* App 是否订阅油门通知 */
static volatile uint8_t g_throttle_value = 0;
static volatile uint8_t g_speed_value = 0;       /* 当前车速 KM/H（App 写入） */
static volatile uint8_t g_mode = 0;              /* 0=HID 通道, 1=GATT 通道 */
static volatile uint8_t g_hat = HAT_NULL;         /* 方向帽状态：上=0 / 下=4 / 空=8 */
static portMUX_TYPE g_state_mux = portMUX_INITIALIZER_UNLOCKED;

static int actual_gear = 0;                      // a：APP 实际挡位（发按键 ±1）
static volatile bool shift_enabled = true;       // 换挡开关（NVS 持久化，默认开）
static uint32_t hold_start_time = 0;             // 保持时间开始时间戳
static int hold_direction = 0;                   // 当前保持方向：1=升挡，-1=降挡，0=无
static uint32_t last_gear_log_tick = 0;          // 换挡诊断日志节流

static adc_oneshot_unit_handle_t adc_handle;
static int calibrated_min_raw = -1;
static int calibrated_max_raw = -1;

/* ======================= 函数声明 ======================= */
static void ble_init(void);
static void ble_on_sync(void);
static void ble_host_task(void *param);
static int gap_event_handler(struct ble_gap_event *event, void *arg);
static void advertise(void);
static int gatt_svr_access(uint16_t conn_handle, uint16_t attr_handle,
                           struct ble_gatt_access_ctxt *ctxt, void *arg);
static int send_report(void);
static int send_throttle_notify(void);
static void press_key(uint8_t hat_value);
static void adc_init(void);
static uint8_t read_throttle(void);
static int speed_to_zone(int speed);
static uint32_t get_hold_time(int from_gear);
static void gear_up_one(void);
static void gear_down_one(void);
static void throttle_task(void *pv);
static void gear_task(void *pv);
static void load_calibration_from_nvs(void);
static void record_max_now(void);
static void record_min_now(void);
static void load_shift_enabled_from_nvs(void);
static void save_shift_enabled(void);
static void load_mode_from_nvs(void);
static void save_mode(void);

/* ======================= GATT 服务表 ======================= */
static const struct ble_gatt_svc_def gatt_svcs[] = {
    {
        .type = BLE_GATT_SVC_TYPE_PRIMARY,
        .uuid = &hid_svc_uuid.u,
        .characteristics = (struct ble_gatt_chr_def[]) {
            { .uuid = &hid_info_char_uuid.u, .access_cb = gatt_svr_access, .flags = BLE_GATT_CHR_F_READ },
            { .uuid = &report_map_char_uuid.u, .access_cb = gatt_svr_access, .flags = BLE_GATT_CHR_F_READ },
            { .uuid = &protocol_mode_char_uuid.u, .access_cb = gatt_svr_access, .flags = BLE_GATT_CHR_F_READ | BLE_GATT_CHR_F_WRITE },
            { .uuid = &report_char_uuid.u, .access_cb = gatt_svr_access,
              .flags = BLE_GATT_CHR_F_READ | BLE_GATT_CHR_F_NOTIFY,
              .val_handle = &report_val_handle,
            },
            { 0 },
        },
    },
    {
        /* 自定义标定服务：可写 0xFFE1 记录最大/最小值；只读 0xFFE2 查询标定状态 */
        .type = BLE_GATT_SVC_TYPE_PRIMARY,
        .uuid = &calib_svc_uuid.u,
        .characteristics = (struct ble_gatt_chr_def[]) {
            { .uuid = &calib_char_uuid.u, .access_cb = gatt_svr_access,
              .flags = BLE_GATT_CHR_F_WRITE,
              .val_handle = &calib_char_handle },
            { .uuid = &calib_status_char_uuid.u, .access_cb = gatt_svr_access,
              .flags = BLE_GATT_CHR_F_READ,
              .val_handle = &calib_status_char_handle },
            { .uuid = &speed_char_uuid.u, .access_cb = gatt_svr_access,
              .flags = BLE_GATT_CHR_F_WRITE,
              .val_handle = &speed_char_handle },
            { .uuid = &shift_char_uuid.u, .access_cb = gatt_svr_access,
              .flags = BLE_GATT_CHR_F_READ | BLE_GATT_CHR_F_WRITE,
              .val_handle = &shift_char_handle },
            { .uuid = &throttle_char_uuid.u, .access_cb = gatt_svr_access,
              .flags = BLE_GATT_CHR_F_READ | BLE_GATT_CHR_F_NOTIFY,
              .val_handle = &throttle_char_handle },
            { .uuid = &mode_char_uuid.u, .access_cb = gatt_svr_access,
              .flags = BLE_GATT_CHR_F_READ | BLE_GATT_CHR_F_WRITE,
              .val_handle = &mode_char_handle },
            { 0 },
        },
    },
    { 0 },
};

/* ======================= ADC 初始化 ======================= */
static void adc_init(void) {
    adc_oneshot_unit_init_cfg_t unit_cfg = { .unit_id = ADC_UNIT_1 };
    ESP_ERROR_CHECK(adc_oneshot_new_unit(&unit_cfg, &adc_handle));
    adc_oneshot_chan_cfg_t chan_cfg = { .atten = ADC_ATTEN, .bitwidth = ADC_WIDTH };
    ESP_ERROR_CHECK(adc_oneshot_config_channel(adc_handle, ADC_CHANNEL, &chan_cfg));
}

/* ======================= NVS 标定读写 ======================= */
static void load_calibration_from_nvs(void) {
    nvs_handle_t h;
    int32_t max_raw = -1;
    int32_t min_raw = -1;
    if (nvs_open("throttle", NVS_READONLY, &h) == ESP_OK) {
        nvs_get_i32(h, "max_raw", &max_raw);
        nvs_get_i32(h, "min_raw", &min_raw);
        nvs_close(h);
    }
    if (max_raw > 0) {
        calibrated_max_raw = max_raw;
        ESP_LOGI(TAG, "从 NVS 读回油门最大值: max_raw=%d", max_raw);
    } else {
        ESP_LOGI(TAG, "NVS 中暂无油门最大值，等待按钮记录");
    }
    if (min_raw > 0) {
        calibrated_min_raw = min_raw;
        ESP_LOGI(TAG, "从 NVS 读回油门最小值: min_raw=%d", min_raw);
    } else {
        ESP_LOGI(TAG, "NVS 中暂无油门最小值，等待按钮记录");
    }
}

static void record_max_now(void) {
    int raw;
    ESP_ERROR_CHECK(adc_oneshot_read(adc_handle, ADC_CHANNEL, &raw));
    calibrated_max_raw = raw;
    nvs_handle_t h;
    if (nvs_open("throttle", NVS_READWRITE, &h) == ESP_OK) {
        nvs_set_i32(h, "max_raw", raw);
        nvs_commit(h);
        nvs_close(h);
        ESP_LOGI(TAG, "已记录油门最大值并写入 NVS: max_raw=%d", raw);
    } else {
        ESP_LOGE(TAG, "写入 NVS 失败");
    }
}

static void record_min_now(void) {
    int raw;
    ESP_ERROR_CHECK(adc_oneshot_read(adc_handle, ADC_CHANNEL, &raw));
    calibrated_min_raw = raw;
    nvs_handle_t h;
    if (nvs_open("throttle", NVS_READWRITE, &h) == ESP_OK) {
        nvs_set_i32(h, "min_raw", raw);
        nvs_commit(h);
        nvs_close(h);
        ESP_LOGI(TAG, "已记录油门最小值并写入 NVS: min_raw=%d", raw);
    } else {
        ESP_LOGE(TAG, "写入 NVS 失败");
    }
}

static void load_shift_enabled_from_nvs(void) {
    nvs_handle_t h;
    int32_t val = 1;
    if (nvs_open("throttle", NVS_READONLY, &h) == ESP_OK) {
        nvs_get_i32(h, "shift_enabled", &val);
        nvs_close(h);
    }
    shift_enabled = (val != 0);
    ESP_LOGI(TAG, "换挡开关: %s", shift_enabled ? "开" : "关");
}

static void save_shift_enabled(void) {
    nvs_handle_t h;
    if (nvs_open("throttle", NVS_READWRITE, &h) == ESP_OK) {
        nvs_set_i32(h, "shift_enabled", shift_enabled ? 1 : 0);
        nvs_commit(h);
        nvs_close(h);
        ESP_LOGI(TAG, "换挡开关已保存: %s", shift_enabled ? "开" : "关");
    }
}

/* 油门通道模式持久化：开发板重启后恢复上次模式，避免重启后回到 HID 导致油门走错通道 */
static void load_mode_from_nvs(void) {
    nvs_handle_t h;
    int32_t val = 0;
    if (nvs_open("throttle", NVS_READONLY, &h) == ESP_OK) {
        nvs_get_i32(h, "mode", &val);
        nvs_close(h);
    }
    g_mode = (val != 0) ? 1 : 0;
    ESP_LOGI(TAG, "油门通道模式: %s", g_mode ? "GATT" : "HID");
}

static void save_mode(void) {
    nvs_handle_t h;
    if (nvs_open("throttle", NVS_READWRITE, &h) == ESP_OK) {
        nvs_set_i32(h, "mode", g_mode ? 1 : 0);
        nvs_commit(h);
        nvs_close(h);
        ESP_LOGI(TAG, "油门通道模式已保存: %s", g_mode ? "GATT" : "HID");
    }
}

/* ======================= 油门读取 + 标定 ======================= */
static uint8_t read_throttle(void) {
    int raw;
    ESP_ERROR_CHECK(adc_oneshot_read(adc_handle, ADC_CHANNEL, &raw));

    /* 完全手动标定：最小值、最大值都由 App 按钮记录，未记录完整则输出 0 */
    if (calibrated_min_raw <= 0 || calibrated_max_raw <= calibrated_min_raw) {
        return 0;
    }

    /* EMA 平滑 + 线性映射（原始码域内比例换算） */
    static float ema = 0;
    static bool ema_first = true;
    if (ema_first) { ema = raw; ema_first = false; }
    else { ema = EMA_ALPHA * ema + (1.0f - EMA_ALPHA) * raw; }
    int smooth_raw = (int)(ema + 0.5f);
    int range = calibrated_max_raw - calibrated_min_raw;
    if (range <= 0) range = 1;
    int extended = (smooth_raw - calibrated_min_raw) * (MAP_EXTEND_HIGH - MAP_EXTEND_LOW) / range + MAP_EXTEND_LOW;
    if (extended < 0) extended = 0;
    if (extended > 255) extended = 255;
    return (uint8_t)extended;
}

/* ======================= 车速 → 档位 =======================
 * 0 km/h → 0 档；1~11 → 1 档；12~22 → 2 档；23~38 → 3 档；39+ → 4 档。
 * 直接映射（无滞回），跨多档跳变由 gear_task 的 a 追赶 b 逐级追平，
 * 保持时间负责防抖。 */
static int speed_to_zone(int speed) {
    if (speed <= 0) return 0;
    if (speed <= 11) return 1;
    if (speed <= 22) return 2;
    if (speed <= 38) return 3;
    return 4;
}

static uint32_t get_hold_time(int from_gear) {
    switch (from_gear) {
        case 0: return HOLD_GEAR1_MS;
        case 1: return HOLD_GEAR2_MS;
        case 2: return HOLD_GEAR3_MS;
        case 3: return HOLD_GEAR4_MS;
        default: return 0xFFFFFFFF;
    }
}

static int send_report(void) {
    if (conn_handle == BLE_HS_CONN_HANDLE_NONE) return -1;
    if (!notify_enabled) return -2;
    gamepad_report_t rep = {0};
    taskENTER_CRITICAL(&g_state_mux);
    /* HID 通道（g_mode==0）才把油门放进 RT；GATT 通道下 RT 置 0，油门走 GATT 通知 */
    rep.accelerator = (g_mode == 0) ? g_throttle_value : 0;
    rep.buttons_hat = (uint8_t)(g_hat << 4);   /* 高 4 位放方向帽，按钮恒 0 */
    taskEXIT_CRITICAL(&g_state_mux);
    struct os_mbuf *om = ble_hs_mbuf_from_flat(&rep, sizeof(rep));
    if (!om) return -3;
    return ble_gattc_notify_custom((uint16_t)conn_handle, report_val_handle, om);
}

/* GATT 油门通知：把当前油门值 notify 给后台 App */
static int send_throttle_notify(void) {
    if (conn_handle == BLE_HS_CONN_HANDLE_NONE) return -1;
    if (!throttle_notify_enabled) return -2;
    uint8_t val;
    taskENTER_CRITICAL(&g_state_mux);
    val = g_throttle_value;
    taskEXIT_CRITICAL(&g_state_mux);
    struct os_mbuf *om = ble_hs_mbuf_from_flat(&val, 1);
    if (!om) return -3;
    return ble_gattc_notify_custom((uint16_t)conn_handle, throttle_char_handle, om);
}

/* ======================= 模拟按键 ======================= */
static void press_key(uint8_t hat_value) {
    /* 按下（方向帽推到指定方向） */
    taskENTER_CRITICAL(&g_state_mux);
    g_hat = hat_value;
    taskEXIT_CRITICAL(&g_state_mux);
    send_report();
    vTaskDelay(pdMS_TO_TICKS(150));   /* 保持150ms */

    /* 释放（方向帽回中） */
    taskENTER_CRITICAL(&g_state_mux);
    g_hat = HAT_NULL;
    taskEXIT_CRITICAL(&g_state_mux);
    send_report();

    /* 释放后80ms再补发一次，帮助APP确认按键已抬起 */
    vTaskDelay(pdMS_TO_TICKS(80));
    send_report();

    vTaskDelay(pdMS_TO_TICKS(20));   /* 释放后静默20ms */
}

/* ======================= 换挡（a 记录真实发送的按键） ========================
 * a = actual_gear：发 UP 就 +1，发 DOWN 就 -1，永远等于 APP 实际挡位。
 * 换挡期间油门任务继续运行：RT 报告与按键报告都携带完整快照（互斥锁保护）。 */
static void gear_up_one(void) {
    if (actual_gear >= 4) return;
    press_key(HAT_UP);
    actual_gear++;
}

static void gear_down_one(void) {
    if (actual_gear <= 0) return;
    press_key(HAT_DOWN);
    actual_gear--;
}

/* ======================= GATT 访问回调 ======================= */
static int gatt_svr_access(uint16_t conn_handle, uint16_t attr_handle,
                           struct ble_gatt_access_ctxt *ctxt, void *arg) {
    (void)conn_handle;
    (void)attr_handle;
    (void)arg;
    switch (ctxt->op) {
    case BLE_GATT_ACCESS_OP_READ_CHR:
        if (ble_uuid_cmp(ctxt->chr->uuid, &hid_info_char_uuid.u) == 0) {
            os_mbuf_append(ctxt->om, hid_info_value, sizeof(hid_info_value)); return 0;
        }
        if (ble_uuid_cmp(ctxt->chr->uuid, &report_map_char_uuid.u) == 0) {
            os_mbuf_append(ctxt->om, hid_descriptor, sizeof(hid_descriptor)); return 0;
        }
        if (ble_uuid_cmp(ctxt->chr->uuid, &protocol_mode_char_uuid.u) == 0) {
            uint8_t mode = 1;   /* Report 模式 */
            os_mbuf_append(ctxt->om, &mode, 1); return 0;
        }
        if (ble_uuid_cmp(ctxt->chr->uuid, &report_char_uuid.u) == 0) {
            gamepad_report_t rep = {0};
            taskENTER_CRITICAL(&g_state_mux);
            rep.accelerator = (g_mode == 0) ? g_throttle_value : 0;
            rep.buttons_hat = (uint8_t)(g_hat << 4);
            taskEXIT_CRITICAL(&g_state_mux);
            os_mbuf_append(ctxt->om, &rep, sizeof(rep));
            return 0;
        }
        if (ble_uuid_cmp(ctxt->chr->uuid, &calib_status_char_uuid.u) == 0) {
            uint8_t status = (calibrated_min_raw > 0 && calibrated_max_raw > calibrated_min_raw) ? 0x01 : 0x00;
            os_mbuf_append(ctxt->om, &status, 1);
            return 0;
        }
        if (ble_uuid_cmp(ctxt->chr->uuid, &shift_char_uuid.u) == 0) {
            uint8_t val = shift_enabled ? 1 : 0;
            os_mbuf_append(ctxt->om, &val, 1);
            return 0;
        }
        if (ble_uuid_cmp(ctxt->chr->uuid, &throttle_char_uuid.u) == 0) {
            uint8_t val;
            taskENTER_CRITICAL(&g_state_mux);
            val = g_throttle_value;
            taskEXIT_CRITICAL(&g_state_mux);
            os_mbuf_append(ctxt->om, &val, 1);
            return 0;
        }
        if (ble_uuid_cmp(ctxt->chr->uuid, &mode_char_uuid.u) == 0) {
            uint8_t val = g_mode;
            os_mbuf_append(ctxt->om, &val, 1);
            return 0;
        }
        break;
    case BLE_GATT_ACCESS_OP_WRITE_CHR:
        if (ble_uuid_cmp(ctxt->chr->uuid, &protocol_mode_char_uuid.u) == 0) {
            uint8_t val;
            if (os_mbuf_copydata(ctxt->om, 0, 1, &val) != 0) return BLE_ATT_ERR_UNLIKELY;
            if (val > 1) return BLE_ATT_ERR_UNLIKELY;  /* 仅接受 0(boot)/1(report) */
            return 0;
        }
        if (ble_uuid_cmp(ctxt->chr->uuid, &calib_char_uuid.u) == 0) {
            uint8_t val;
            if (os_mbuf_copydata(ctxt->om, 0, 1, &val) != 0) return BLE_ATT_ERR_UNLIKELY;
            if (val == 0x01) {
                record_max_now();
            } else if (val == 0x02) {
                record_min_now();
            }
            return 0;
        }
        if (ble_uuid_cmp(ctxt->chr->uuid, &speed_char_uuid.u) == 0) {
            uint8_t val;
            if (os_mbuf_copydata(ctxt->om, 0, 1, &val) != 0) return BLE_ATT_ERR_UNLIKELY;
            g_speed_value = val;
            return 0;
        }
        if (ble_uuid_cmp(ctxt->chr->uuid, &shift_char_uuid.u) == 0) {
            uint8_t val;
            if (os_mbuf_copydata(ctxt->om, 0, 1, &val) != 0) return BLE_ATT_ERR_UNLIKELY;
            shift_enabled = (val != 0);
            save_shift_enabled();
            ESP_LOGI(TAG, "换挡开关切换为: %s", shift_enabled ? "开" : "关");
            return 0;
        }
        if (ble_uuid_cmp(ctxt->chr->uuid, &mode_char_uuid.u) == 0) {
            uint8_t val;
            if (os_mbuf_copydata(ctxt->om, 0, 1, &val) != 0) return BLE_ATT_ERR_UNLIKELY;
            g_mode = (val != 0) ? 1 : 0;
            save_mode();
            ESP_LOGI(TAG, "油门通道切换为: %s", g_mode ? "GATT" : "HID");
            return 0;
        }
        break;
    default:
        break;
    }
    return BLE_ATT_ERR_UNLIKELY;
}

/* ======================= GAP 事件处理 ========================
 * 注意：此回调运行在 NimBLE 协议栈任务里，禁止任何阻塞延时，
 * 旧代码在 CONNECT 里 vTaskDelay(500ms) 会卡住整个蓝牙协议栈。 */
static int gap_event_handler(struct ble_gap_event *event, void *arg) {
    switch (event->type) {
    case BLE_GAP_EVENT_CONNECT:
        if (event->connect.status == 0) {
            conn_handle = event->connect.conn_handle;
            ESP_LOGI(TAG, "手机已连接 conn=%d，发起配对加密", event->connect.conn_handle);
            ble_gap_security_initiate(event->connect.conn_handle);
        } else {
            advertise();
        }
        return 0;
    case BLE_GAP_EVENT_ENC_CHANGE:
        ESP_LOGI(TAG, "加密结果 status=%d", event->enc_change.status);
        if (event->enc_change.status == 0) {
            struct ble_gap_upd_params params = {
                .itvl_min = 6,   /* 7.5 ms */
                .itvl_max = 12,  /* 15 ms  */
                .latency = 0,
                .supervision_timeout = 400,
                .min_ce_len = 0,
                .max_ce_len = 0,
            };
            if (conn_handle != BLE_HS_CONN_HANDLE_NONE) {
                ble_gap_update_params((uint16_t)conn_handle, &params);
            }
        }
        return 0;
    case BLE_GAP_EVENT_DISCONNECT:
        ESP_LOGI(TAG, "已断开 reason=%d，重新广播", event->disconnect.reason);
        conn_handle = BLE_HS_CONN_HANDLE_NONE;
        notify_enabled = false;
        throttle_notify_enabled = false;
        taskENTER_CRITICAL(&g_state_mux);
        g_hat = HAT_NULL;
        taskEXIT_CRITICAL(&g_state_mux);
        advertise();
        return 0;
    case BLE_GAP_EVENT_SUBSCRIBE:
        ESP_LOGI(TAG, "订阅事件: attr_handle=%d (report=%d) notify=%d",
                 event->subscribe.attr_handle, report_val_handle, event->subscribe.cur_notify);
        if (event->subscribe.attr_handle == report_val_handle) {
            notify_enabled = event->subscribe.cur_notify;
            if (notify_enabled) {
                send_report();
            }
        }
        if (event->subscribe.attr_handle == throttle_char_handle) {
            throttle_notify_enabled = event->subscribe.cur_notify;
            if (throttle_notify_enabled) {
                send_throttle_notify();
            }
        }
        return 0;
    case BLE_GAP_EVENT_REPEAT_PAIRING: {
        struct ble_gap_conn_desc desc;
        if (ble_gap_conn_find(event->repeat_pairing.conn_handle, &desc) == 0) {
            ble_store_util_delete_peer(&desc.peer_id_addr);
        }
        return BLE_GAP_REPEAT_PAIRING_RETRY;
    }
    case BLE_GAP_EVENT_ADV_COMPLETE:
        advertise();
        return 0;
    default:
        return 0;
    }
}

/* ======================= 广播 ======================= */
static void advertise(void) {
    struct ble_gap_adv_params adv_params = {0};
    adv_params.conn_mode = BLE_GAP_CONN_MODE_UND;
    adv_params.disc_mode = BLE_GAP_DISC_MODE_GEN;
    adv_params.itvl_min = BLE_GAP_ADV_FAST_INTERVAL1_MIN;
    adv_params.itvl_max = BLE_GAP_ADV_FAST_INTERVAL1_MAX;
    struct ble_hs_adv_fields fields = {0};
    fields.flags = APP_ADV_FLAGS;
    fields.tx_pwr_lvl_is_present = 1;
    fields.tx_pwr_lvl = BLE_HS_ADV_TX_PWR_LVL_AUTO;
    fields.appearance = 0x03C4;   /* HID Gamepad（标准外观值） */
    fields.appearance_is_present = 1;
    fields.name = (uint8_t *)DEVICE_NAME;
    fields.name_len = strlen(DEVICE_NAME);
    fields.name_is_complete = 1;
    int rc = ble_gap_adv_set_fields(&fields);
    if (rc) { return; }
    rc = ble_gap_adv_start(BLE_OWN_ADDR_PUBLIC, NULL, BLE_HS_FOREVER, &adv_params, gap_event_handler, NULL);
    if (rc) { return; }
}

static void ble_on_sync(void) {
    advertise();
}

static void ble_init(void) {
    nimble_port_init();
    ble_att_set_preferred_mtu(128);
    ble_hs_cfg.sm_io_cap      = BLE_SM_IO_CAP_NO_IO;
    ble_hs_cfg.sm_bonding     = 1;
    ble_hs_cfg.sm_mitm        = 0;
    ble_hs_cfg.sm_sc          = 1;
    ble_hs_cfg.sm_our_key_dist  = BLE_SM_PAIR_KEY_DIST_ENC | BLE_SM_PAIR_KEY_DIST_ID;
    ble_hs_cfg.sm_their_key_dist = BLE_SM_PAIR_KEY_DIST_ENC | BLE_SM_PAIR_KEY_DIST_ID;
    ble_hs_cfg.sync_cb = ble_on_sync;
    /* 绑定密钥持久化到 NVS：断电重连无需重新配对 */
    ble_store_config_init();
    ble_svc_gap_init();
    ble_svc_gatt_init();
    ble_gatts_count_cfg(gatt_svcs);
    ble_gatts_add_svcs(gatt_svcs);
    ble_svc_gap_device_name_set(DEVICE_NAME);
    nimble_port_freertos_init(ble_host_task);
}

static void ble_host_task(void *param) {
    nimble_port_run();
    nimble_port_freertos_deinit();
}

/* ======================= 油门采样任务 ========================
 * 10ms 周期（100Hz），任何变化都发送；200ms 心跳重发防止丢包。 */
static void throttle_task(void *pv) {
    uint8_t last_sent = 255;
    uint8_t last_gatt_sent = 255;
    TickType_t last_send_tick = 0;
    TickType_t last_log_tick = 0;
    while (1) {
        uint8_t rt = read_throttle();
        taskENTER_CRITICAL(&g_state_mux);
        g_throttle_value = rt;
        taskEXIT_CRITICAL(&g_state_mux);

        TickType_t now = xTaskGetTickCount();
        if (rt != last_sent || (now - last_send_tick) >= pdMS_TO_TICKS(200)) {
            if (send_report() == 0) {   /* 发送成功才记录 */
                last_sent = rt;
                last_send_tick = now;
            }
        }
        /* GATT 通道模式：额外把油门 notify 给后台 App */
        if (g_mode == 1 && (rt != last_gatt_sent || (now - last_send_tick) >= pdMS_TO_TICKS(200))) {
            if (send_throttle_notify() == 0) {
                last_gatt_sent = rt;
            }
        }
        if (now - last_log_tick >= pdMS_TO_TICKS(500)) {
            last_log_tick = now;
            ESP_LOGI(TAG, "RT=%u mode=%d 已发HID=%u 已发GATT=%u",
                     rt, (int)g_mode, last_sent, last_gatt_sent);
        }
        vTaskDelay(pdMS_TO_TICKS(10));
    }
}

/* ======================= 换挡任务（a 追赶 b + 保持时间）=======================
 * a = actual_gear：记录真实发送的按键（发 UP 就 +1，发 DOWN 就 -1），
 *                  永远等于 APP 实际挡位。保持时间只决定"何时发按键"，
 *                  不改变 a 的计数（a 只在真实发按键后才 ±1）。
 * b = target_gear：根据车速（App 通过 BLE 写入）算出的期望挡位。
 * 追赶无时无刻进行：每当 b 和 a 出现差值，就发按键去"持平"；但发按键前
 * 需要 b 保持在当前差值方向够时间（升挡用长保持，降挡用短保持），
 * 这样既让 b 在等待期间快速更新，又避免按键太频繁导致 App 漏识别。
 * 换挡开关（shift_enabled）关闭时不发任何换挡键。 */
static void gear_task(void *pv) {
    int target_gear = 0;
    while (1) {
        /* 换挡开关关闭：不发换挡键 */
        if (!shift_enabled) {
            vTaskDelay(pdMS_TO_TICKS(50));
            continue;
        }

        taskENTER_CRITICAL(&g_state_mux);
        uint8_t speed = g_speed_value;
        taskEXIT_CRITICAL(&g_state_mux);

        /* 更新 b（车速 → 档位，直接映射） */
        target_gear = speed_to_zone(speed);
        uint32_t now = xTaskGetTickCount() * portTICK_PERIOD_MS;

        /* 诊断日志（节流） */
        if (now - last_gear_log_tick >= 500) {
            last_gear_log_tick = now;
            ESP_LOGI(TAG, "gear speed=%u target=%d actual=%d", speed, target_gear, actual_gear);
        }

        /* 每当 b != a，先保持够时间再发按键追赶 */
        if (target_gear == actual_gear) {
            hold_start_time = 0;
            hold_direction = 0;
        } else {
            int dir = (target_gear > actual_gear) ? 1 : -1;
            if (hold_direction != dir) {
                hold_direction = dir;
                hold_start_time = now;
            }
            uint32_t required = (dir > 0) ? get_hold_time(actual_gear) : DOWN_SHIFT_HOLD_MS;
            if ((uint32_t)(now - hold_start_time) >= required) {
                if (dir > 0) gear_up_one();
                else gear_down_one();
                hold_start_time = 0;
                hold_direction = 0;
            }
        }

        vTaskDelay(pdMS_TO_TICKS(50));
    }
}

/* ======================= 主函数 ======================= */
void app_main(void) {
    /* 关闭所有日志输出（含 NimBLE 内部日志与本模块调试日志） */
    esp_log_level_set("*", ESP_LOG_NONE);

    /* 初始化 NVS：必须在任何 nvs 读写（load_calibration_from_nvs）之前 */
    esp_err_t ret = nvs_flash_init();
    if (ret == ESP_ERR_NVS_NO_FREE_PAGES || ret == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        nvs_flash_erase();
        ret = nvs_flash_init();
    }
    ESP_ERROR_CHECK(ret);

    adc_init();
    load_calibration_from_nvs();
    load_shift_enabled_from_nvs();
    load_mode_from_nvs();
    ble_init();

    xTaskCreate(throttle_task, "throttle", 3072, NULL, 5, NULL);
    xTaskCreate(gear_task, "gear", 2048, NULL, 4, NULL);
}
