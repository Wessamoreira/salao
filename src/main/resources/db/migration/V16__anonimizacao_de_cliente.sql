-- RT-CLI-005 — anonimização a pedido do titular.

-- ---------------------------------------------------------------------------
-- 1. Uma correção que a anonimização obrigou a enxergar.
--
-- RN-CLI-007 mandava o NOME da cliente para a trilha de auditoria no cadastro.
-- A regra já dizia que telefone e e-mail não podiam ir, "para não criar uma
-- segunda base de dados pessoais fora do alcance da anonimização" — e deixava
-- o nome passar.
--
-- É a mesma falha, e a anonimização a torna visível: apagar o nome da tabela
-- `cliente` deixaria o nome recuperável em `auditoria` pelo `entidade_id`. Pior,
-- numa tabela que a aplicação nem pode alterar.
--
-- A trilha responde "quem fez o quê", e `entidade_id` já diz de quem se trata.
-- O nome não acrescentava nada e criava o vazamento.
--
-- As linhas já gravadas são redigidas aqui. É uma escrita deliberada numa
-- tabela append-only, feita pelo owner numa migração revisada — o único
-- caminho aceitável, e por isso está escrito e não escondido num script.
-- ---------------------------------------------------------------------------
update auditoria
   set depois = depois - 'nome'
 where entidade = 'cliente'
   and depois ? 'nome';

-- ---------------------------------------------------------------------------
-- 2. Texto livre no consentimento sai.
--
-- `observacao` era campo livre digitado pela recepção, e campo livre recebe PII
-- mais cedo ou mais tarde ("pediu para parar, mudou de número, avisar a irmã").
--
-- A tabela é append-only por permissão: `salao_app` não tem UPDATE. Então a
-- anonimização NÃO CONSEGUE limpar esse texto — e conceder UPDATE para
-- resolver desfaria a garantia que faz o registro valer como prova.
--
-- Restava escolher entre enfraquecer a prova e remover o campo. O campo sai:
-- canal, data, decisão e quem registrou já respondem "onde está a prova de que
-- ela aceitou?". O texto livre acrescentava pouco e criava PII num lugar de
-- onde não há como tirá-la.
-- ---------------------------------------------------------------------------
alter table cliente_consentimento drop column observacao;
