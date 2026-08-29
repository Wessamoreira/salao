-- RT-IAM-001 — provisionar estabelecimento.
--
-- Fecha a pendência anotada em V3: criar um estabelecimento novo é a única
-- operação legitimamente CROSS-TENANT do sistema, e até aqui ela não tinha
-- caminho nenhum.
--
-- Por que salao_app não serve: a policy tenant_isolado tem
-- with check (id = current_setting('app.tenant_id')), então a aplicação só
-- conseguiria inserir uma linha cujo id fosse o do tenant que ela já é. Para
-- criar um tenant que ainda não existe, isso é inútil.
--
-- Provisionamento é operação de PLATAFORMA, não de tenant — a mesma categoria
-- de purga e retenção (ADR-0010). Vai pela role de manutenção.

drop policy manutencao on estabelecimento;

create policy manutencao on estabelecimento
  to salao_manutencao
  using      (true)
  with check (true);

grant insert, update on estabelecimento to salao_manutencao;

-- Endurecimento: a aplicação nunca cria estabelecimento. O grant existia desde
-- V3 e era inofensivo só por acidente da policy — permissão que não é exercida
-- deve ser removida, não deixada dependendo de uma segunda camada para não
-- causar dano.
revoke insert on estabelecimento from salao_app;
