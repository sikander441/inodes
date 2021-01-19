package inodes.service;

import inodes.Configuration;
import inodes.service.api.UserService;
import inodes.service.api.NoSuchUserException;
import inodes.service.api.UnAuthorizedException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.sql.*;
import java.util.*;

@Service
public class DBBasedUserService extends UserService {

    @Autowired
    Configuration conf;

    Map<String, User> users = new HashMap<>();
    Connection CONN;
    Random R = new Random();

    @PostConstruct
    void init() throws Exception {
        try {
            CONN = DriverManager.getConnection(conf.getProperty("authservice.db.url"), conf.getProperty("authservice.db.user"), conf.getProperty("authservice.db.password"));
            try {
                CONN.createStatement().execute("CREATE TABLE users (username VARCHAR(32) PRIMARY KEY, fullname VARCHAR(128), password VARCHAR(64), roles VARCAR(128), verified INT, teamsurl VARCHAR(256), email VARCHAR(64), regtok VARCHAR(64))");
            } catch (Exception ex) {
                if(!ex.getMessage().contains("name is already used by an existing object")) {
                    ex.printStackTrace();
                }
            }
            _register(new User("mmp", "Madhusoodan Pataki", "m@123", true, "CREATE,DELETE,EDIT,UPVOTE,DOWNVOTE,COMMENT", "", "mpataki@informatica.com"));
            _register(new User("admin", "Admin", "a@123", true, "CREATE,DELETE,EDIT,UPVOTE,DOWNVOTE,COMMENT", "", "mpataki@informatica.com"));
        } catch (Exception throwables) {
            throwables.printStackTrace();
        }
    }

    @Override
    public boolean authenticate(User cred) throws Exception {
        User c = getUser(cred.getUser());
        if (c != null) {
            return c.isVerified() && c.getPassword().equals(cred.getPassword());
        }
        return false;
    }

    @Override
    public void validate(String uid, String tok) throws Exception {
        User u = getUser(uid);
        if(!u.__getRegTok().equals(tok)) {
            throw new Exception("token don't match, re-register");
        }
        PreparedStatement ps = CONN.prepareStatement("UPDATE users SET verified=1 WHERE username=?");
        ps.setString(1, uid);
        try {
            ps.executeUpdate();
        } catch (Exception e) {
            e.printStackTrace();
            throw new Exception("Registration failed" + e.getMessage());
        }
    }

    @Override
    public void _register(User cred) throws Exception {
        PreparedStatement ps = CONN.prepareStatement("INSERT INTO users (username, fullname, password, roles, verified, teamsurl, email, regtok) VALUES (?, ?, ?, ?, ?, ?, ?, ?)");
        String tok = R.nextDouble() + "-" + R.nextInt();
        ps.setString(1, cred.getUser());
        ps.setString(2, cred.getFullName());
        ps.setString(3, cred.getPassword());
        ps.setString(4, cred.getRoles());
        ps.setInt(5, cred.isVerified() ? 1 : 0);
        ps.setString(6, cred.getTeamsUrl());
        ps.setString(7, cred.getEmail());
        ps.setString(8, tok);
        try {
            ps.executeUpdate();
            users.put(cred.getUser(), cred);
            cred.setRegTok(tok);
        } catch (Exception e) {
            e.printStackTrace();
            throw new Exception("Registration failed" + e.getMessage()); // mask the exception
        }
    }

    @Override
    public User getUser(String userName) throws Exception {
        User cred = users.get(userName);
        if (cred != null) {
            return cred;
        }
        return _getUsers(userName).get(0);
    }

    @Override
    public boolean isAdmin(String userId) {
        return userId.equals("admin");
    }

    @Override
    public List<User> getUsers() throws Exception {
        return _getUsers(null);
    }

    @Override
    public void _updateUser(String userId, User u) throws Exception {

    }

    public List<User> _getUsers(String userName) throws Exception {
        PreparedStatement ps = CONN.prepareStatement(
                String.format(
                        "SELECT * FROM users %s",
                        userName == null ? "" : "WHERE username=?"
                )
        );
        if (userName != null) {
            ps.setString(1, userName);
        }
        boolean ok = ps.execute();
        if (!ok) {
            throw new NoSuchUserException(userName == null ? "" : userName);
        }
        List<User> users = new LinkedList<>();
        try {
            ResultSet rs = ps.getResultSet();
            while (rs.next()) {
                users.add(new User(
                        rs.getString("username"),
                        rs.getString("fullname"),
                        rs.getString("password"),
                        rs.getInt("verified") == 1,
                        rs.getString("roles"),
                        rs.getString("teamsurl"),
                        rs.getString("email"),
                        rs.getString("regtok")
                ));
            }
            return users;
        } catch (Exception e) {
            e.printStackTrace();
            throw new NoSuchUserException(userName);
        }
    }

    private String makeCredentialLine(User cred) {
        return String.format("%s:%s:%s:%s", cred.getUser(), cred.getPassword(), cred.getRoles(), cred.isVerified());
    }

}