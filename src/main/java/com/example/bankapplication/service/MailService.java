package com.example.bankapplication.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/** Thin wrapper around JavaMailSender so callers (PinResetService) don't depend on it directly and sending
 *  failures degrade the same way Kafka notifications already do elsewhere in this app: logged, never thrown,
 *  since a reachable mail server is not guaranteed in every environment this runs in. */
@Service
public class MailService {
    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    private final JavaMailSender mailSender;
    private final String fromAddress;

    public MailService(JavaMailSender mailSender, @Value("${bank.mail.from:}") String fromAddress) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
    }

    /** Returns whether the mail server accepted the message (false = it was only logged). */
    public boolean send(String to, String subject, String body) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            if (!fromAddress.isBlank()) {
                message.setFrom(fromAddress);
            }
            message.setTo(to);
            message.setSubject(subject);
            message.setText(body);
            mailSender.send(message);
            return true;
        } catch (MailException e) {
            log.error("Failed to send email to {}: {}", to, e.getMessage());
            return false;
        }
    }

    /** Same as send(), with one file attached. Returns whether the mail server accepted it. */
    public boolean sendWithAttachment(String to, String subject, String body, String filename, byte[] content, String contentType) {
        try {
            jakarta.mail.internet.MimeMessage message = mailSender.createMimeMessage();
            org.springframework.mail.javamail.MimeMessageHelper helper =
                    new org.springframework.mail.javamail.MimeMessageHelper(message, true, "UTF-8");
            if (!fromAddress.isBlank()) {
                helper.setFrom(fromAddress);
            }
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(body);
            helper.addAttachment(filename, new org.springframework.core.io.ByteArrayResource(content), contentType);
            mailSender.send(message);
            return true;
        } catch (MailException | jakarta.mail.MessagingException e) {
            log.error("Failed to send email with attachment to {}: {}", to, e.getMessage());
            return false;
        }
    }
}
