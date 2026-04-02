package ru.itmo.hhprocess.security;

import java.util.HashSet;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.itmo.hhprocess.entity.UserEntity;
import ru.itmo.hhprocess.enums.UserRole;
import ru.itmo.hhprocess.repository.UserRepository;

@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;
    private final XmlCredentialStore xmlCredentialStore;

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        String normalizedUsername = username == null ? "" : username.trim().toLowerCase();

        var xmlUser = xmlCredentialStore.findByEmail(normalizedUsername)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));

        UserEntity user = userRepository.findWithRolesByEmail(normalizedUsername)
                .orElseThrow(() -> new UsernameNotFoundException("User profile not found: " + username));

        Set<GrantedAuthority> authorities = new HashSet<>();
        user.getRoles().forEach(role -> {
            authorities.add(new SimpleGrantedAuthority("ROLE_" + role.getCode()));
            UserRole userRole = UserRole.valueOf(role.getCode());
            RolePrivileges.privilegesFor(userRole).forEach(privilege ->
                    authorities.add(new SimpleGrantedAuthority(privilege.name())));
        });

        return new User(
                user.getEmail(),
                xmlUser.getPasswordHash(),
                user.isEnabled(),
                true, true, true,
                authorities
        );
    }
}
