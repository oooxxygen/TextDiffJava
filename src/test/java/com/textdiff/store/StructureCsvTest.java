package com.textdiff.store;

import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StructureCsvTest {

    @Test
    void kindOfByFileNameConvention() {
        assertEquals(StructureCsv.Kind.TYPE_PARM,
                StructureCsv.kindOf("bat_report_type_parm_20260913.csv", new byte[0]));
        assertEquals(StructureCsv.Kind.CONF_FIELD,
                StructureCsv.kindOf("bat_report_conf_field_20260913.csv", new byte[0]));
        assertEquals(StructureCsv.Kind.UNKNOWN, StructureCsv.kindOf("random.csv", new byte[0]));
    }

    @Test
    void kindOfByHeaderWhenNameUnknown() {
        byte[] typeCsv = "report_id,report_file_name,parm_report_type\nR1,f.txt,T1\n"
                .getBytes(StandardCharsets.UTF_8);
        byte[] fieldCsv = "report_id,field_name,field_format\nR1,a,VARCHAR\n"
                .getBytes(StandardCharsets.UTF_8);
        assertEquals(StructureCsv.Kind.TYPE_PARM, StructureCsv.kindOf("类型表.csv", typeCsv));
        assertEquals(StructureCsv.Kind.CONF_FIELD, StructureCsv.kindOf("字段表.csv", fieldCsv));
    }

    @Test
    void parseTypesMapsFileNicknameType() {
        byte[] csv = """
                report_id,report_file_name,parm_report_type
                bocso,bocso.txt,对公存款
                bocsoxc,bocsoxc.txt,对公存款
                ,,
                """.getBytes(StandardCharsets.UTF_8);
        List<FieldMaps.ReportType> out = StructureCsv.parseTypes(csv, "t.csv");
        assertEquals(2, out.size());
        assertEquals("bocso", out.get(0).reportId());
        assertEquals("bocso.txt", out.get(0).reportFileName());
        assertEquals("对公存款", out.get(0).parmReportType());
        assertEquals("t.csv", out.get(0).sourceFile());
    }

    @Test
    void parseFieldsRowOrderPerReport() {
        byte[] csv = """
                report_id,field_name,field_format
                R1,客户号,CHAR
                R1,余额,DECIMAL
                R2,序号,INT
                R1,币种,CHAR
                """.getBytes(StandardCharsets.UTF_8);
        List<FieldMaps.ReportField> out = StructureCsv.parseFields(csv, "f.csv");
        assertEquals(4, out.size());
        // 同一 report_id 内自 0 递增，跨 report 独立计数
        assertEquals(0, out.get(0).colIndex());
        assertEquals(1, out.get(1).colIndex());
        assertEquals(0, out.get(2).colIndex());
        assertEquals(2, out.get(3).colIndex());
        assertEquals("余额", out.get(1).fieldName());
        assertEquals("DECIMAL", out.get(1).fieldFormat());
    }

    @Test
    void parseFieldsExplicitSeqColumnOneBased() {
        byte[] csv = """
                report_id,field_seq,field_name,field_format
                R1,3,c,CHAR
                R1,1,a,CHAR
                R1,2,b,CHAR
                """.getBytes(StandardCharsets.UTF_8);
        List<FieldMaps.ReportField> out = StructureCsv.parseFields(csv, "f.csv");
        // 序号列 1-based：3→2、1→0、2→1
        assertEquals(2, out.get(0).colIndex());
        assertEquals(0, out.get(1).colIndex());
        assertEquals(1, out.get(2).colIndex());
    }

    @Test
    void gbkFallbackDecodesChinese() {
        byte[] gbk = "report_id,field_name\nR1,账户余额\n".getBytes(Charset.forName("GBK"));
        List<FieldMaps.ReportField> out = StructureCsv.parseFields(gbk, "f.csv");
        assertEquals(1, out.size());
        assertEquals("账户余额", out.get(0).fieldName());
    }

    @Test
    void utf8BomStripped() {
        byte[] bom = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] csv = concat(bom, "report_id,field_name\nR1,客户名\n".getBytes(StandardCharsets.UTF_8));
        List<FieldMaps.ReportField> out = StructureCsv.parseFields(csv, "f.csv");
        assertEquals("客户名", out.get(0).fieldName());
    }

    @Test
    void quotedFieldsWithCommaAndEscapedQuote() {
        byte[] csv = "report_id,field_name,field_format\n\"R1\",\"name,with,comma\",\"say \"\"hi\"\"\"\n"
                .getBytes(StandardCharsets.UTF_8);
        List<FieldMaps.ReportField> out = StructureCsv.parseFields(csv, "f.csv");
        assertEquals("name,with,comma", out.get(0).fieldName());
        assertEquals("say \"hi\"", out.get(0).fieldFormat());
    }

    @Test
    void missingRequiredColumnsThrows() {
        byte[] bad = "foo,bar\n1,2\n".getBytes(StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> StructureCsv.parseTypes(bad, "x.csv"));
        assertThrows(IllegalArgumentException.class, () -> StructureCsv.parseFields(bad, "x.csv"));
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
