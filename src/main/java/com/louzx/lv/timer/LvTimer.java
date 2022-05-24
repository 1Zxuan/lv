package com.louzx.lv.timer;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.louzx.swipe.core.constants.Method;
import com.louzx.swipe.core.utils.HttpClientUtils;
import com.louzx.swipe.core.utils.SwipeUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author louzx
 * @date 2022/5/24
 */

public class LvTimer {

    private final JdbcTemplate jdbcTemplateLv;
    private final JdbcTemplate jdbcTemplateWx;
    private final Logger logger = LoggerFactory.getLogger(LvTimer.class);
    private final Map<String, String> header = new HashMap<>();

    @Autowired
    public LvTimer(JdbcTemplate jdbcTemplateLv, JdbcTemplate jdbcTemplateWx) {
        this.jdbcTemplateLv = jdbcTemplateLv;
        this.jdbcTemplateWx = jdbcTemplateWx;

        try {
            HttpClientUtils.trustAllHttpsCertificates("TLSv1.2");
            HttpClientUtils.setDefReadTimeOut(10000);
            HttpClientUtils.setDefConnectionTimeOut(10000);
        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            logger.error(">>>>>>>>设置HTTP请求失败");
            System.exit(0);
        }

        header.put("user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/101.0.4951.67 Safari/537.36");
        header.put("accept-encoding", "gzip, deflate, br");
        header.put("accept-language", "zh-CN,zh;q=0.9,en;q=0.8");
        header.put("cache-control", "no-cache");
    }

    @Scheduled(cron = "0/30 * * * * ?")
    public void check() {
        List<Map<String, Object>> list = jdbcTemplateLv.queryForList("SELECT id, check_url, notify_uid FROM lv_check WHERE closed = '0' and check_url is not null and  notify_uid is not null ");
        if (SwipeUtils.canList(list)) {
            for (Map<String, Object> map : list) {
                String checkUrl = String.valueOf(map.get("check_url"));
                String res = HttpClientUtils.doHttp(checkUrl, Method.GET, header, null);
                if (StringUtils.isNotBlank(res)) {
                    logger.info(">>>>>>>>任务：【{}】返回结果：【{}】", map.get("id"), res);
                    res = res.replace("@", "");
                    try {
                        JSONObject obj = JSONObject.parseObject(res);
                        JSONArray skuAvailability = obj.getJSONArray("skuAvailability");
                        if (SwipeUtils.canList(skuAvailability)) {
                            JSONObject item = skuAvailability.getJSONObject(0);
                            if (item.getBooleanValue("exists") && item.getBooleanValue("inStock")) {
                                String skuId = item.getString("skuId");
                                String notifyUid = String.valueOf(map.get("notify_uid"));
                                String[] users = notifyUid.split(",");
                                for (String user : users) {
                                    jdbcTemplateWx.update("INSERT INTO push_info (content, summary, input_date, status, send_uid) SELECT ?, '【苦蛋】LV商品库存监控', SYSDATE(), 0, ?", skuId + "有库存", user);
                                }
                                jdbcTemplateLv.update("UPDATE lv_check SET CLOSED = '1', LASTDATE = SYSDATE() WHERE ID = ? ", map.get("id"));
                            }
                        }
                    } catch (Exception e) {
                        logger.error(">>>>>>>>链接：【{}】返回：【{}】非JSON", checkUrl, res);
                    }
                } else {
                    logger.error(">>>>>>>>链接：【{}】返回为空", checkUrl);
                }
            }
        }
    }

}
