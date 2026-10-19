package com.fredoseep.motion;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Formatting of published search counters, with no live Minecraft reads. */
public final class FusionSearchHud {
    private FusionSearchHud(){}
    public static List<String> lines(Map<String,Object> metrics,boolean shortLogTask){
        if(metrics.isEmpty())return List.of();
        int stage=((Number)metrics.get("search_stage")).intValue();
        String phase=stage==1?(shortLogTask?"首级优化·原木仅此一级":"首级优化"):"第 "+stage+" 级·首解执行";
        String solution="暂无完整解";
        Object cost=metrics.get("complete_cost_ticks");
        if(cost instanceof Number n)solution="已有完整解 "+String.format(Locale.ROOT,"%.1f",n.doubleValue())+" ticks";
        if("VERIFYING".equals(metrics.get("reason")))solution="完整路线回放验证中";
        return List.of("Fusion 分级："+phase,
                "状态 "+metrics.get("known")+" / "+metrics.get("stage_known_limit")+"；最高 "+metrics.get("max_known")+"；"+solution);
    }
}
