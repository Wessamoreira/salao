-- Correção de RT-NOT-001 — mensagem que não saiu não pode dizer que saiu.
--
-- O único mensageiro de produção (`MensageiroDeRegistro`) registra no log e volta normalmente, e o
-- despachante tratava a volta normal como entrega: toda mensagem virava ENVIADA sem ter saído. O
-- próprio contrato da porta avisava que isso quebraria tudo, e o módulo conversacional (RT-CNV-006)
-- já tinha resolvido o mesmo problema na fila dele, citando este como o exemplo a não seguir.
--
-- O custo era concreto: a cliente diz "não recebi o lembrete", o salão vê "enviada" e conclui que ela
-- ignorou.

-- ---------------------------------------------------------------------------
-- Um estado próprio, e final
--
-- Final de propósito, e é a diferença em relação à fila da equipe: lá a mensagem espera o provedor
-- chegar, porque um "marcado" para a recepção continua servindo depois. Aqui não — um lembrete de um
-- horário de três semanas atrás não pode disparar no dia em que alguém ligar o WhatsApp. Como
-- BLOQUEADA: não é falha, é informação.
-- ---------------------------------------------------------------------------
alter table mensagem drop constraint mensagem_status_check;
alter table mensagem add constraint mensagem_status_check
  check (status in ('PENDENTE', 'ENVIADA', 'FALHOU', 'BLOQUEADA', 'SEM_PROVEDOR'));

-- ---------------------------------------------------------------------------
-- O passado também mentia, e é corrigido aqui
--
-- Toda linha ENVIADA até esta versão passou pelo único mensageiro que existia no código — e ele nunca
-- entregou nada. Não há ENVIADA verdadeira para preservar. Deixá-las como estão manteria a mentira
-- justamente no histórico, que é onde alguém vai procurar a resposta de "por que não recebi".
--
-- `enviada_em` volta a nulo: não houve envio, e uma data de envio que não aconteceu é a mesma mentira
-- em outra coluna.
-- ---------------------------------------------------------------------------
update mensagem
   set status = 'SEM_PROVEDOR',
       enviada_em = null,
       ultimo_erro = 'nenhum provedor de envio configurado'
 where status = 'ENVIADA';

-- A partir daqui, ENVIADA só com a data em que saiu — a mesma garantia da fila da equipe (V55).
alter table mensagem add constraint mensagem_enviada_tem_data
  check (status <> 'ENVIADA' or enviada_em is not null);
