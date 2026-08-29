# ADR-0001: Monólito modular com fronteiras verificadas por teste

- **Status:** aceita · **Data:** 2026-08-28 · **Decisor:** Wesley

## Contexto

Um desenvolvedor, volume de um salão, infra barata. Existe intenção declarada de vender o sistema
para outros estabelecimentos, o que levanta a pergunta "não deveria já nascer em microserviços?".

## Opções consideradas

| Opção | Prós | Contras | Custo de reverter |
|---|---|---|---|
| Monólito tradicional | Rápido de começar | Vira bola de lama; extrair depois é reescrever | Altíssimo |
| **Monólito modular (Spring Modulith)** | Fronteira explícita e testada; um deploy; transação local | Disciplina exigida; fronteira precisa de teste para existir | Baixo |
| Microserviços | Escala independente | Transação distribuída, observabilidade, deploy e infra para uma pessoa só manter | — |

## Decisão

Monólito modular com Spring Modulith. Módulo só acessa outro pela API pública ou por evento.
Zero join entre tabelas de módulos diferentes. Fronteiras verificadas por ArchUnit + Modulith no CI.

## Consequências

**Positivas.** Transação local resolve o fechamento de comanda (estoque + pagamento + comissão)
sem saga. Deploy único cabe numa VM pequena. A extração futura custa o transporte, não o desenho.

**Negativas, assumidas.** A disciplina depende do teste — sem ele a fronteira é só intenção.
Relatórios que cruzam módulos precisam de uma válvula explícita (o schema `relatorio`), senão
alguém quebra a regra em silêncio.

**Revisitar quando.** Houver equipe separada por domínio, ou um módulo tiver perfil de carga
radicalmente diferente. Não antes — deploy separado não é o que torna a fronteira boa.
