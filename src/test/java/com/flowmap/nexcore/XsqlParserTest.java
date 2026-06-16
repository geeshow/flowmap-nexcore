package com.flowmap.nexcore;

import com.flowmap.nexcore.nexcore.XsqlParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class XsqlParserTest {

    @Test
    void extractsPrimaryTablePerOperation() {
        assertEquals("tb_cust_base", XsqlParser.extractTable("select",
                "SELECT KKP_CIF FROM TB_CUST_BASE WHERE KKP_CIF = #{KKP_CIF}"));
        assertEquals("tb_cust_chg_hist", XsqlParser.extractTable("insert",
                "INSERT INTO TB_CUST_CHG_HIST (KKP_CIF) VALUES (#{KKP_CIF})"));
        assertEquals("tb_cust_base", XsqlParser.extractTable("update",
                "UPDATE TB_CUST_BASE SET HNDP = #{HNDP}"));
        assertEquals("ac0216", XsqlParser.extractTable("delete",
                "DELETE FROM AC0216 WHERE BAS_YM = #BAS_YM#"));
        assertEquals("ac0208", XsqlParser.extractTable("select",
                "SELECT A.CIF FROM ac0208 A, ac0204 B WHERE A.bas_ym = #BAS_YM#"));
    }

    @Test
    void stripsSchemaPrefix() {
        assertEquals("tbl", XsqlParser.extractTable("select", "SELECT * FROM MYSCHEMA.TBL"));
    }
}
