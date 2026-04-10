create table if not exists signal_desk_watchlist (
    id varchar(64) primary key,
    market varchar(16) not null,
    ticker varchar(64) not null,
    name varchar(255) not null,
    price integer not null,
    change_rate double precision not null,
    sector varchar(255) not null,
    stance text not null,
    note text not null
);

create table if not exists signal_desk_portfolio_positions (
    id varchar(64) primary key,
    market varchar(16) not null,
    ticker varchar(64) not null,
    name varchar(255) not null,
    buy_price integer not null,
    current_price integer not null,
    quantity integer not null,
    profit_amount integer not null,
    evaluation_amount integer not null,
    profit_rate double precision not null
);

create table if not exists signal_desk_paper_positions (
    id varchar(64) primary key,
    market varchar(16) not null,
    ticker varchar(64) not null,
    name varchar(255) not null,
    average_price integer not null,
    current_price integer not null,
    quantity integer not null,
    return_rate double precision not null
);

create table if not exists signal_desk_paper_trades (
    id varchar(64) primary key,
    trade_date varchar(32) not null,
    side varchar(16) not null,
    market varchar(16) not null,
    ticker varchar(64) not null,
    name varchar(255) not null,
    price integer not null,
    quantity integer not null
);

create table if not exists signal_desk_ai_picks (
    id varchar(64) primary key,
    market varchar(16) not null,
    ticker varchar(64) not null,
    name varchar(255) not null,
    basis text not null,
    confidence integer not null,
    note text not null,
    expected_return_rate double precision not null
);

create table if not exists signal_desk_ai_track_records (
    id varchar(64) primary key,
    recommended_date varchar(32) not null,
    market varchar(16) not null,
    ticker varchar(64) not null,
    name varchar(255) not null,
    entry_price integer not null,
    latest_price integer not null,
    realized_return_rate double precision not null,
    success boolean not null
);
