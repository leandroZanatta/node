create table if not exists auditoria_execucao (
	id bigserial primary key,
	iniciou_em timestamptz not null default now(),
	versao_executor int not null,
	configuracao jsonb not null
);

create table if not exists auditoria_envio (
	hash text primary key,
	execucao_id bigint not null references auditoria_execucao (id),
	altura bigint not null,
	caminho text not null,
	rota_id text not null,
	pools text[] not null,
	chegada_ms bigint not null,
	fracao_milha int not null,
	lance_wei_por_gas numeric,
	lucro_bnb_wei numeric,
	gas_para_o_lance numeric,
	detalhes jsonb not null,
	registrado_em timestamptz not null default clock_timestamp()
);
create index if not exists auditoria_envio_altura on auditoria_envio (altura);
create index if not exists auditoria_envio_execucao on auditoria_envio (execucao_id);

create table if not exists auditoria_recusa (
	hash text primary key,
	execucao_id bigint not null references auditoria_execucao (id),
	motivo text not null,
	detalhes jsonb not null,
	registrado_em timestamptz not null default clock_timestamp()
);

create table if not exists auditoria_autopsia (
	hash text primary key,
	status text,
	desfecho text,
	veredito text,
	detalhes jsonb not null,
	registrado_em timestamptz not null default clock_timestamp()
);

create table if not exists auditoria_desfecho (
	hash text primary key,
	classe text not null,
	folga_ms bigint,
	posicao int,
	quem text,
	preco_gas_frente_wei numeric,
	topo_wei numeric,
	veredito text,
	registrado_em timestamptz not null default clock_timestamp()
);
create index if not exists auditoria_desfecho_classe on auditoria_desfecho (classe);

create table if not exists auditoria_arbitragem (
	altura bigint not null,
	indice int not null,
	gatilho int not null,
	token text,
	sobra_wei numeric,
	pools text[] not null,
	v2 int not null,
	v3 int not null,
	observado_em timestamptz not null default now(),
	primary key (altura, indice, gatilho)
);

create table if not exists auditoria_snapshot (
	id bigserial primary key,
	instante timestamptz not null default now(),
	dados jsonb not null
);
