package br.com.salao.financeiro.internal.domain;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.util.HashSet;
import java.util.Set;

/**
 * RT-FIN-012 — em que dias o banco liquida (RN-FIN-038).
 *
 * <h2>Não é o dia em que o salão abre</h2>
 *
 * <p>O salão trabalha no feriado; o dinheiro, não. Por isso este calendário não tem nada a ver com a
 * jornada da equipe nem com os bloqueios da agenda — ele responde uma pergunta de banco.
 *
 * <h2>Derivado, e não digitado</h2>
 *
 * <p>Os feriados bancários nacionais são conhecidos: nove datas fixas e três que andam com a Páscoa.
 * Pedir ao dono que digite doze datas por ano seria transformar regra conhecida em trabalho manual — e
 * em erro no ano em que ele esquecer.
 *
 * <p>24 e 31 de dezembro entram porque não há liquidação neles, ainda que não sejam feriado civil.
 *
 * <p><strong>Feriado municipal fica de fora</strong>, e é a limitação conhecida: muda por cidade e não há
 * de onde derivá-lo. A previsão erra por um dia nesses casos.
 */
public final class CalendarioBancario {

    private CalendarioBancario() {
    }

    public static boolean ehDiaUtil(LocalDate dia) {
        if (dia.getDayOfWeek() == DayOfWeek.SATURDAY || dia.getDayOfWeek() == DayOfWeek.SUNDAY) {
            return false;
        }
        return !feriados(dia.getYear()).contains(dia);
    }

    /** O próprio dia, se já for útil; senão o primeiro útil depois dele. */
    public static LocalDate proximoDiaUtil(LocalDate dia) {
        LocalDate candidato = dia;
        // O maior bloco possível é Natal caindo na quinta com o fim de semana e 31/12 em volta;
        // dez voltas é folga larga, e o limite existe para nunca haver laço infinito.
        for (int i = 0; i < 10 && !ehDiaUtil(candidato); i++) {
            candidato = candidato.plusDays(1);
        }
        return candidato;
    }

    static Set<LocalDate> feriados(int ano) {
        Set<LocalDate> datas = new HashSet<>();
        datas.add(LocalDate.of(ano, Month.JANUARY, 1));
        datas.add(LocalDate.of(ano, Month.APRIL, 21));
        datas.add(LocalDate.of(ano, Month.MAY, 1));
        datas.add(LocalDate.of(ano, Month.SEPTEMBER, 7));
        datas.add(LocalDate.of(ano, Month.OCTOBER, 12));
        datas.add(LocalDate.of(ano, Month.NOVEMBER, 2));
        datas.add(LocalDate.of(ano, Month.NOVEMBER, 15));
        // Consciência Negra: feriado nacional desde a Lei 14.759/2023.
        datas.add(LocalDate.of(ano, Month.NOVEMBER, 20));
        datas.add(LocalDate.of(ano, Month.DECEMBER, 25));
        // Sem liquidação, ainda que não sejam feriado civil.
        datas.add(LocalDate.of(ano, Month.DECEMBER, 24));
        datas.add(LocalDate.of(ano, Month.DECEMBER, 31));

        LocalDate pascoa = pascoa(ano);
        datas.add(pascoa.minusDays(48));  // segunda de carnaval
        datas.add(pascoa.minusDays(47));  // terça de carnaval
        datas.add(pascoa.minusDays(2));   // sexta-feira santa
        datas.add(pascoa.plusDays(60));   // corpus christi
        return datas;
    }

    /** Domingo de Páscoa pelo algoritmo de Gauss (calendário gregoriano). */
    static LocalDate pascoa(int ano) {
        int a = ano % 19;
        int b = ano / 100;
        int c = ano % 100;
        int d = b / 4;
        int e = b % 4;
        int f = (b + 8) / 25;
        int g = (b - f + 1) / 3;
        int h = (19 * a + b - d - g + 15) % 30;
        int i = c / 4;
        int k = c % 4;
        int l = (32 + 2 * e + 2 * i - h - k) % 7;
        int m = (a + 11 * h + 22 * l) / 451;
        int mes = (h + l - 7 * m + 114) / 31;
        int dia = ((h + l - 7 * m + 114) % 31) + 1;
        return LocalDate.of(ano, mes, dia);
    }
}
