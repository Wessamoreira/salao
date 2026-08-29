-- RT-INF-001 — baseline. Só extensões: cada tabela nasce na rotina que a usa.

create extension if not exists btree_gist;   -- exclusion constraint da agenda (RN-AGD-004)
create extension if not exists pg_trgm;      -- busca de cliente por nome (R-UX-03)
create extension if not exists unaccent;     -- "joao" encontra "João"
