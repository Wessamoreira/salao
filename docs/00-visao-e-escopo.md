# Visão e escopo

## O problema

Salão de beleza opera hoje com agenda em caderno ou planilha, comissão de profissional PJ
calculada à mão no fim do mês, estoque sem controle de validade e cliente marcando por
WhatsApp com a recepção transcrevendo tudo manualmente. Os quatro sangram dinheiro:
no-show sem confirmação, comissão errada, produto vencido, horário duplicado.

## O que o sistema faz

Agenda multiprofissional com recurso escasso e tempo de processamento · comanda com múltiplos
profissionais e comissões distintas · apuração e fechamento de comissão PJ · estoque por lote
com FEFO e validade · conciliação de pagamento com a maquininha · agente no WhatsApp que agenda
por texto ou áudio, sempre em nome de um usuário real e com as permissões dele.

## Personas

| Persona | Como usa | Onde | O que não pode ver |
|---|---|---|---|
| Administrador / dono | Configura tudo, agenda para qualquer um, financeiro completo | Web + WhatsApp | — |
| Gerente | Opera o dia, vê financeiro do salão | Web | Configuração estrutural |
| Profissional (PJ) | Só a própria agenda e o próprio extrato | Web + WhatsApp | Agenda e faturamento de outros |
| Recepção | Agenda para todos, abre/fecha comanda, cobra, vende | Web | Comissão e custo de produto |
| Painel do balcão | Agenda do dia consolidada, somente leitura | Web quiosque | Qualquer valor |
| Cliente final | Sem login. WhatsApp (só a partir da Fase 4) | WhatsApp | Tudo que não seja dele |

## Métrica de sucesso por fase

Não é linha de código. É isto:

| Fase | O sistema está pronto quando |
|---|---|
| 0 | Login funciona, teste de vazamento de tenant passa, deploy em hmg é automático |
| 1 | A recepção roda um dia inteiro sem abrir a planilha |
| 2 | O fechamento do mês bate com a conta feita à mão pelo dono |
| 3 | Inventário físico bate com o saldo do sistema |
| 4 | Cliente agenda por áudio, ponta a ponta, com auditoria completa da ação da IA |
| 5 | Divergência de cartão aparece na tela e não some sozinha |
| 6 | Nenhuma sugestão de preço é aplicada sem clique humano |

## Fora de escopo da v1 — explicitamente

Emissão fiscal (NFS-e/NFC-e) · app nativo · folha de pagamento CLT · marketplace de clientes ·
programa de fidelidade e pontos · multi-unidade com estoque compartilhado · comissão em
cascata de indicação · integração contábil.

Cada um desses tem um pedido natural do dono do salão no meio do desenvolvimento. A resposta
padrão é: entra no backlog da v2, não na v1. Escopo que vaza é o que mata projeto de um dev só.

## Restrições

- **Um desenvolvedor.** O plano assume isso. Toda fase precisa ser útil sozinha, porque pode ser
  a última que dá tempo de fazer.
- **Infra barata.** VM pequena. Sem Kubernetes, sem Kafka, sem Redis até haver gatilho objetivo.
- **Multi-tenant desde o dia 0**, mesmo com um único salão em produção, porque colar isolamento
  depois é reescrever todas as queries.
