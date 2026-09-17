-- ========================================
-- Test Database Schema
-- ========================================
-- This schema supports all integration test scenarios
-- Tables: users, user_access, audit_log
-- ========================================

-- Drop tables if they exist (for test cleanup)
DROP TABLE IF EXISTS user_access;
DROP TABLE IF EXISTS users;
DROP TABLE IF EXISTS audit_log;
DROP TABLE IF EXISTS sap_users;

-- ========================================
-- Users Table
-- ========================================
CREATE TABLE users (
    id INT AUTO_INCREMENT PRIMARY KEY,
    user_id VARCHAR(100) NOT NULL UNIQUE,
    first_name VARCHAR(100) NOT NULL,
    last_name VARCHAR(100) NOT NULL,
    email VARCHAR(255) NOT NULL,
    department VARCHAR(100),
    job_title VARCHAR(100),
    employee_id VARCHAR(50),
    phone VARCHAR(50),
    city VARCHAR(100),
    country VARCHAR(100),
    start_date DATE,
    active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- Indexes for common queries
CREATE INDEX idx_users_email ON users(email);
CREATE INDEX idx_users_department ON users(department);
CREATE INDEX idx_users_active ON users(active);

-- ========================================
-- User Access Table
-- ========================================
CREATE TABLE user_access (
    id INT AUTO_INCREMENT PRIMARY KEY,
    user_id VARCHAR(100) NOT NULL,
    system_name VARCHAR(100) NOT NULL,
    access_level VARCHAR(50) NOT NULL,
    granted_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    revoked_date TIMESTAMP NULL,
    granted_by VARCHAR(100) DEFAULT 'SYSTEM',
    CONSTRAINT fk_access_user FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE,
    CONSTRAINT unique_user_system UNIQUE (user_id, system_name)
);

-- Indexes for access queries
CREATE INDEX idx_access_user ON user_access(user_id);
CREATE INDEX idx_access_system ON user_access(system_name);
CREATE INDEX idx_access_active ON user_access(revoked_date);

-- ========================================
-- Audit Log Table
-- ========================================
CREATE TABLE audit_log (
    id INT AUTO_INCREMENT PRIMARY KEY,
    user_id VARCHAR(100),
    action VARCHAR(50) NOT NULL,
    entity_type VARCHAR(50) NOT NULL,
    entity_id VARCHAR(100),
    details TEXT,
    performed_by VARCHAR(100) DEFAULT 'SYSTEM',
    performed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Index for audit queries
CREATE INDEX idx_audit_user ON audit_log(user_id);
CREATE INDEX idx_audit_action ON audit_log(action);
CREATE INDEX idx_audit_date ON audit_log(performed_at);

-- ========================================
-- SAP Users Table (target-application stand-in for scenario tests)
-- ========================================
CREATE TABLE sap_users (
    username VARCHAR(50),
    status VARCHAR(20)
);

-- ========================================
-- Sample Data (Optional - for manual testing)
-- ========================================

-- Sample user
INSERT INTO users (user_id, first_name, last_name, email, department, job_title, active)
VALUES ('sample.user', 'Sample', 'User', 'sample.user@test.com', 'IT', 'Test User', true);

-- Sample access
INSERT INTO user_access (user_id, system_name, access_level)
VALUES ('sample.user', 'TEST_SYSTEM', 'USER');

-- Sample audit entry
INSERT INTO audit_log (user_id, action, entity_type, entity_id, details)
VALUES ('sample.user', 'CREATE', 'USER', 'sample.user', 'Test user created');
