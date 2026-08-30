-- RT-INF-012 — endurecimento operacional.

-- ---------------------------------------------------------------------------
-- Timeouts POR ROLE, e não por configuração do cliente.
--
-- No cliente, o valor depende de alguém lembrar de configurá-lo em cada
-- ambiente. Na role, ele acompanha a credencial: qualquer conexão que se
-- autentique como salao_app herda os limites, inclusive um psql aberto às
-- pressas durante um incidente — que é justamente quando alguém roda um SELECT
-- sem WHERE numa tabela grande.
-- ---------------------------------------------------------------------------

-- 30s cobre com folga qualquer consulta desta aplicação. Uma que passe disso é
-- defeito, não carga — e sem teto ela segura a conexão até o fim do mundo.
alter role salao_app set statement_timeout = '30s';

-- 5s é o teto que faltava para o custo que RT-INF-005 assumiu por escrito: a
-- segunda requisição idempotente concorrente espera a primeira commitar. "Espera"
-- sem limite não é um custo, é uma falha esperando acontecer.
alter role salao_app set lock_timeout = '5s';

-- Transação aberta e esquecida segura VACUUM e acumula bloqueio. 60s é muito
-- mais do que qualquer caso de uso legítimo daqui.
alter role salao_app set idle_in_transaction_session_timeout = '60s';

-- Manutenção varre tabela inteira: purga de auditoria com anos de registro leva
-- mais que 30s legitimamente. Limite próprio, mais largo, ainda finito.
alter role salao_manutencao set statement_timeout = '10min';
alter role salao_manutencao set lock_timeout = '30s';

comment on database salao is
  'Timeouts vivem nas roles (V11), não no cliente: acompanham a credencial.';
