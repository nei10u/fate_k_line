package com.nei10u.fate.service;

import com.nei10u.fate.model.FateRequest;
import com.nei10u.fate.model.FateResponse;
import com.nlf.calendar.EightChar;
import com.nlf.calendar.Lunar;
import com.nlf.calendar.Solar;
import com.nlf.calendar.eightchar.DaYun;
import com.nlf.calendar.eightchar.Yun;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

@Service
public class FateCalculationService {

    public FateResponse.BaZiInfo calculate(FateRequest req) {
        // 1. 基础阳历时间
        Calendar c = Calendar.getInstance();
        c.set(req.getYear(), req.getMonth() - 1, req.getDay(), req.getHour(), req.getMinute(), 0);
        // 2. 真太阳时计算 (核心逻辑)
        // 如果有经度，需要调整时间：每差1度，时间差4分钟。基准是东经120度。
        if (req.getLongitude() != null) {
            double offsetMinutes = (req.getLongitude() - 120.0) * 4;
            c.add(Calendar.MINUTE, (int) offsetMinutes);
            // 这里更新时间用于排盘
        }

        // 使用调整后的时间生成 Solar 对象
        Solar solar = Solar.fromYmdHms(
                c.get(Calendar.YEAR),
                c.get(Calendar.MONTH) + 1,
                c.get(Calendar.DAY_OF_MONTH),
                c.get(Calendar.HOUR_OF_DAY),
                c.get(Calendar.MINUTE),
                0
        );

        // 3. 转阴历并获取八字
        Lunar lunar = solar.getLunar();
        EightChar eightChar = lunar.getEightChar();

        // 设置流派：2代表晚子时算第二天（根据需求调整，lunar-java默认支持多种流派）
        eightChar.setSect(2);

        // 4. 起大运
        // gender: 1男，0女
        int gender = "男".equals(req.getGender()) ? 1 : 0;
        Yun yun = eightChar.getYun(gender);

        // 5. 封装结果
        FateResponse.BaZiInfo info = new FateResponse.BaZiInfo();
        info.setYearPillar(eightChar.getYearGan() + eightChar.getYearZhi());
        info.setMonthPillar(eightChar.getMonthGan() + eightChar.getMonthZhi());
        info.setDayPillar(eightChar.getDayGan() + eightChar.getDayZhi());
        info.setHourPillar(eightChar.getTimeGan() + eightChar.getTimeZhi());

        info.setSolarTime(solar.toYmdHms());
        info.setLunarDate(lunar.toString());

        // 6. 获取大运列表 (修正点：使用 getDaYun() 返回数组)
        List<FateResponse.DaYunInfo> daYunList = new ArrayList<>();
        DaYun[] daYunArr = yun.getDaYun();

        // 通常取前10步大运
        // 注意：lunar-java 的 getDaYun() 返回所有大运，第0个通常是起运前
        for (int i = 1; i < daYunArr.length && i <= 10; i++) {
            DaYun dy = daYunArr[i];
            FateResponse.DaYunInfo dyInfo = new FateResponse.DaYunInfo();
            dyInfo.setStartAge(dy.getStartAge());
            dyInfo.setStartYear(dy.getStartYear());
            dyInfo.setGanZhi(dy.getGanZhi());
            daYunList.add(dyInfo);
        }
        info.setDaYunList(daYunList);

        return info;
    }

    /**
     * 获取指定年份的干支（用于流年K线）
     */
    public String getYearGanZhi(int year) {
        return Lunar.fromYmd(year, 1, 1).getYearInGanZhi();
    }
}