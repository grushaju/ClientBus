package kit.penny.clientbus.server.connector.telegram.client;

import kit.penny.clientbus.server.persistence.repository.MessageRepository;
import kit.penny.clientbus.server.service.MessageService;
import org.drinkless.tdlib.TdApi;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.mockito.Mockito.*;

class TelegramMessageSendListenerTest {
    private final UUID account=UUID.randomUUID();
    private final MessageService messages=mock(MessageService.class);
    private final MessageRepository repository=mock(MessageRepository.class);

    @Test void successJournalsChatAndPendingIdentityWithoutARegisteredMessage() {
        var listener=new TelegramMessageSendListener.Succeeded(account,repository,messages);
        var update=new TdApi.UpdateMessageSendSucceeded();
        update.oldMessageId=-10; update.message=new TdApi.Message(); update.message.id=20; update.message.chatId=-30;
        listener.handleNotification(update);
        verify(messages).recordExternalOutcome(account,"-30","-10","20",false);
        verifyNoInteractions(repository);
    }

    @Test void failureUsesTheSameCorrelationAndDoesNotLookUpTheLatestMessage() {
        var listener=new TelegramMessageSendListener.Failed(account,repository,messages);
        var update=new TdApi.UpdateMessageSendFailed();
        update.oldMessageId=10; update.message=new TdApi.Message(); update.message.id=20; update.message.chatId=30;
        update.error=new TdApi.Error(500,"private provider details");
        listener.handleNotification(update);
        verify(messages).recordExternalOutcome(account,"30","10","20",true);
        verifyNoInteractions(repository);
    }

    @Test void incompleteUpdatesCannotCreateUnscopedCorrelations() {
        var success=new TelegramMessageSendListener.Succeeded(account,repository,messages);
        var failed=new TelegramMessageSendListener.Failed(account,repository,messages);
        success.handleNotification(null); failed.handleNotification(null);
        success.handleNotification(new TdApi.UpdateMessageSendSucceeded());
        var update=new TdApi.UpdateMessageSendFailed(); update.oldMessageId=10;
        update.message=new TdApi.Message(); update.message.id=20;
        failed.handleNotification(update);
        verifyNoInteractions(messages,repository);
    }
}
