-- 坐席信息表
CREATE TABLE IF NOT EXISTS sip_agent (
    id BIGINT PRIMARY KEY,
    extension VARCHAR(50) NOT NULL,
    domain VARCHAR(100) NOT NULL,
    password VARCHAR(100) NOT NULL,
    agent_id BIGINT,
    tenant_id BIGINT,
    display_name VARCHAR(100)
);

-- FreeSWITCH 节点表
CREATE TABLE IF NOT EXISTS sip_fs_node (
    id BIGINT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    sip_ip VARCHAR(50) NOT NULL,
    sip_port INT NOT NULL,
    esl_ip VARCHAR(50),
    esl_port INT,
    status INT DEFAULT 1
);

-- 网关信息表
CREATE TABLE IF NOT EXISTS sip_gateway (
    id BIGINT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    address VARCHAR(50) NOT NULL,
    port INT NOT NULL,
    external_line_number VARCHAR(50),
    from_domain VARCHAR(100),
    caller_id_in_from INT DEFAULT 1,
    auth_type INT DEFAULT 0,
    transport_protocol INT DEFAULT 1,
    auth_address VARCHAR(50),
    auth_port INT,
    username VARCHAR(100),
    password VARCHAR(100),
    retry_seconds INT,
    ping_seconds INT,
    expire_seconds INT,
    status INT DEFAULT 0
);
