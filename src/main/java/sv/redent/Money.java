package sv.redent;

import java.math.*;

public final class Money {
    public static final BigDecimal IVA=new BigDecimal("0.13");
    private Money() {}
    public static BigDecimal round(BigDecimal n) { return n.setScale(2,RoundingMode.HALF_UP); }
    public static String words(BigDecimal value) {
        BigDecimal n=round(value);
        return spanish(n.longValue())+" DÓLARES CON "+String.format("%02d",n.remainder(BigDecimal.ONE).movePointRight(2).intValue())+"/100";
    }
    private static String spanish(long n) {
        String[] small={"CERO","UNO","DOS","TRES","CUATRO","CINCO","SEIS","SIETE","OCHO","NUEVE","DIEZ","ONCE","DOCE","TRECE","CATORCE","QUINCE","DIECISÉIS","DIECISIETE","DIECIOCHO","DIECINUEVE","VEINTE","VEINTIUNO","VEINTIDÓS","VEINTITRÉS","VEINTICUATRO","VEINTICINCO","VEINTISÉIS","VEINTISIETE","VEINTIOCHO","VEINTINUEVE"};
        if(n<30) return small[(int)n];
        if(n<100) return new String[]{"","","","TREINTA","CUARENTA","CINCUENTA","SESENTA","SETENTA","OCHENTA","NOVENTA"}[(int)n/10]+(n%10==0?"":" Y "+spanish(n%10));
        if(n==100) return "CIEN";
        if(n<1000) return new String[]{"","CIENTO","DOSCIENTOS","TRESCIENTOS","CUATROCIENTOS","QUINIENTOS","SEISCIENTOS","SETECIENTOS","OCHOCIENTOS","NOVECIENTOS"}[(int)n/100]+(n%100==0?"":" "+spanish(n%100));
        if(n<1000000) return (n/1000==1?"MIL":spanish(n/1000)+" MIL")+(n%1000==0?"":" "+spanish(n%1000));
        return (n/1000000==1?"UN MILLÓN":spanish(n/1000000)+" MILLONES")+(n%1000000==0?"":" "+spanish(n%1000000));
    }
}
