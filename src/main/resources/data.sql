-- 坐席：extension=1001, domain=sipproxy.example, password=123456
INSERT INTO sip_agent (id, extension, domain, password, agent_id, tenant_id, display_name)
SELECT 1, '1001', 'sipproxy.example', '123456', 1, 1, '测试坐席'
WHERE NOT EXISTS (SELECT 1 FROM sip_agent WHERE id = 1);

-- FS 节点：127.0.0.1:5060, status=1(启用)
INSERT INTO sip_fs_node (id, name, sip_ip, sip_port, esl_ip, esl_port, status)
SELECT 1, 'fs-test', '127.0.0.1', 5060, '127.0.0.1', 8021, 1
WHERE NOT EXISTS (SELECT 1 FROM sip_fs_node WHERE id = 1);

-- 网关：127.0.0.1:5080, status=0(启用)
INSERT INTO sip_gateway (id, name, address, port, external_line_number, from_domain, caller_id_in_from, auth_type, transport_protocol, status)
SELECT 1, 'test-gw', '127.0.0.1', 5080, '10086', 'sipproxy.example', 1, 0, 1, 0
WHERE NOT EXISTS (SELECT 1 FROM sip_gateway WHERE id = 1);
